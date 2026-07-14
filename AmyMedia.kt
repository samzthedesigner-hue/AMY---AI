package com.amy.assistant

import android.content.Context
import android.media.AudioManager
import android.view.ViewGroup
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * AmyMedia - Group 3: Play, Pause, Cinema Full/Medium/Small, Volume (Features 17-21)
 * Uses ExoPlayer (Media3). Player lifecycle is managed by whichever Activity/Fragment
 * hosts the PlayerView; this object wraps playback control logic.
 */
object AmyMedia {

    enum class CinemaMode { FULL, MEDIUM, SMALL }

    private var exoPlayer: ExoPlayer? = null

    fun initPlayer(context: Context): ExoPlayer {
        if (exoPlayer == null) {
            exoPlayer = ExoPlayer.Builder(context).build()
            AmyLogger.i("AmyMedia", "ExoPlayer initialized")
        }
        return exoPlayer!!
    }

    fun attachToView(playerView: PlayerView) {
        playerView.player = exoPlayer
    }

    fun loadAndPlay(url: String) {
        val player = exoPlayer ?: return
        val mediaItem = MediaItem.fromUri(url)
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
        AmyLogger.i("AmyMedia", "Loaded and playing: $url")
    }

    // Feature 17: Play
    fun play() {
        exoPlayer?.play()
        AmyLogger.i("AmyMedia", "Play")
    }

    // Feature 18: Pause
    fun pause() {
        exoPlayer?.pause()
        AmyLogger.i("AmyMedia", "Pause")
    }

    fun stop() {
        exoPlayer?.stop()
    }

    fun release() {
        exoPlayer?.release()
        exoPlayer = null
    }

    // Features 19-20-21: Cinema Full / Medium / Small
    fun applyCinemaMode(playerView: PlayerView, mode: CinemaMode, screenWidthPx: Int, screenHeightPx: Int) {
        val params = playerView.layoutParams
        when (mode) {
            CinemaMode.FULL -> {
                params.width = ViewGroup.LayoutParams.MATCH_PARENT
                params.height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            CinemaMode.MEDIUM -> {
                params.width = (screenWidthPx * 0.75).toInt()
                params.height = (screenHeightPx * 0.5).toInt()
            }
            CinemaMode.SMALL -> {
                params.width = (screenWidthPx * 0.4).toInt()
                params.height = (screenHeightPx * 0.25).toInt()
            }
        }
        playerView.layoutParams = params
        AmyLogger.i("AmyMedia", "Cinema mode applied: $mode")
    }

    // Feature 21: Volume
    fun setVolume(context: Context, level: Int) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val clamped = level.coerceIn(0, maxVolume)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, 0)
            AmyLogger.i("AmyMedia", "Volume set to $clamped/$maxVolume")
        } catch (ex: Exception) {
            AmyLogger.e("AmyMedia", "Failed to set volume", ex)
        }
    }

    fun getVolumePercent(context: Context): Int {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max == 0) 0 else (current * 100) / max
        } catch (ex: Exception) {
            AmyLogger.e("AmyMedia", "Failed to read volume", ex)
            0
        }
    }
}
