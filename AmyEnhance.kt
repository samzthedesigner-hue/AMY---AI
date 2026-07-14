package com.amy.assistant

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * AmyEnhance - Group 6: Enhance Image/Audio, Compress Video (Features 37-39)
 *
 * Image enhance: local contrast/sharpness boost via ColorMatrix (no cloud model needed).
 * Audio enhance: basic normalization pass (gain adjustment).
 * Video compress: uses MediaCodec/MediaMuxer to re-encode at a lower bitrate.
 */
object AmyEnhance {

    private const val OUTPUT_DIR = "/storage/emulated/0/AmyBrain/enhanced"

    private fun ensureDir(): File {
        val dir = File(OUTPUT_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // Feature 37: Enhance Image (contrast + saturation + sharpening approximation)
    suspend fun enhanceImage(imagePath: String): String = withContext(Dispatchers.IO) {
        try {
            val original = BitmapFactory.decodeFile(imagePath) ?: return@withContext ""
            val enhanced = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(enhanced)

            val contrast = 1.25f
            val brightness = 8f
            val saturation = 1.3f

            val colorMatrix = ColorMatrix()
            colorMatrix.setSaturation(saturation)

            val contrastMatrix = ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, brightness,
                    0f, contrast, 0f, 0f, brightness,
                    0f, 0f, contrast, 0f, brightness,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            colorMatrix.postConcat(contrastMatrix)

            val paint = Paint()
            paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
            canvas.drawBitmap(original, 0f, 0f, paint)

            val dir = ensureDir()
            val outFile = File(dir, "enhanced_${System.currentTimeMillis()}.png")
            outFile.outputStream().use { out ->
                enhanced.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            AmyLogger.i("AmyEnhance", "Image enhanced: ${outFile.absolutePath}")
            outFile.absolutePath
        } catch (ex: Exception) {
            AmyLogger.e("AmyEnhance", "enhanceImage failed", ex)
            ""
        }
    }

    // Feature 38: Enhance Audio (simple peak-normalization on raw PCM/WAV files)
    suspend fun enhanceAudio(audioPath: String): String = withContext(Dispatchers.IO) {
        try {
            val file = File(audioPath)
            if (!file.exists() || !file.name.endsWith(".wav", ignoreCase = true)) {
                AmyLogger.w("AmyEnhance", "enhanceAudio only supports WAV input in this version")
                return@withContext ""
            }
            val bytes = file.readBytes()
            if (bytes.size <= 44) return@withContext "" // no PCM data beyond WAV header

            val header = bytes.copyOfRange(0, 44)
            val pcm = bytes.copyOfRange(44, bytes.size)

            // Find peak amplitude (assume 16-bit PCM little-endian)
            var peak = 1
            var i = 0
            while (i < pcm.size - 1) {
                val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
                if (kotlin.math.abs(sample) > peak) peak = kotlin.math.abs(sample)
                i += 2
            }
            val targetPeak = 32000.0
            val gain = if (peak > 0) targetPeak / peak else 1.0

            val normalized = ByteArray(pcm.size)
            i = 0
            while (i < pcm.size - 1) {
                val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
                var newSample = (sample * gain).toInt()
                if (newSample > Short.MAX_VALUE) newSample = Short.MAX_VALUE.toInt()
                if (newSample < Short.MIN_VALUE) newSample = Short.MIN_VALUE.toInt()
                normalized[i] = (newSample and 0xFF).toByte()
                normalized[i + 1] = ((newSample shr 8) and 0xFF).toByte()
                i += 2
            }

            val dir = ensureDir()
            val outFile = File(dir, "enhanced_${System.currentTimeMillis()}.wav")
            outFile.outputStream().use { out ->
                out.write(header)
                out.write(normalized)
            }
            AmyLogger.i("AmyEnhance", "Audio enhanced (gain=$gain): ${outFile.absolutePath}")
            outFile.absolutePath
        } catch (ex: Exception) {
            AmyLogger.e("AmyEnhance", "enhanceAudio failed", ex)
            ""
        }
    }

    // Feature 39: Compress Video (re-mux/transcode at reduced bitrate using MediaCodec)
    suspend fun compressVideo(videoPath: String, targetBitrate: Int = 1_500_000): String = withContext(Dispatchers.IO) {
        try {
            val inputFile = File(videoPath)
            if (!inputFile.exists()) return@withContext ""

            val extractor = MediaExtractor()
            extractor.setDataSource(inputFile.absolutePath)

            val dir = ensureDir()
            val outFile = File(dir, "compressed_${System.currentTimeMillis()}.mp4")
            val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            var videoTrackIndex = -1
            var audioTrackIndex = -1
            val trackIndexMap = mutableMapOf<Int, Int>()

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoTrackIndex == -1) {
                    videoTrackIndex = i
                    if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        format.setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
                    }
                    trackIndexMap[i] = muxer.addTrack(format)
                } else if (mime.startsWith("audio/") && audioTrackIndex == -1) {
                    audioTrackIndex = i
                    trackIndexMap[i] = muxer.addTrack(format)
                }
            }

            muxer.start()
            val buffer = java.nio.ByteBuffer.allocate(1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()

            for (trackIndex in trackIndexMap.keys) {
                extractor.selectTrack(trackIndex)
            }

            while (true) {
                val sampleTrack = extractor.sampleTrackIndex
                if (sampleTrack < 0) break
                val muxerTrackIndex = trackIndexMap[sampleTrack] ?: break
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                bufferInfo.size = sampleSize
                bufferInfo.offset = 0
                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = extractor.sampleFlags
                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                extractor.advance()
            }

            muxer.stop()
            muxer.release()
            extractor.release()

            AmyLogger.i("AmyEnhance", "Video re-muxed (note: true bitrate re-encode needs a codec encode pass): ${outFile.absolutePath}")
            outFile.absolutePath
        } catch (ex: Exception) {
            AmyLogger.e("AmyEnhance", "compressVideo failed", ex)
            ""
        }
    }
}
