package com.amy.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AmyBackup - Features 52-53: Backup with passphrase, Restore with key.
 *
 * Flow:
 *  1. User supplies a passphrase.
 *  2. Passphrase -> SHA-256 -> 32-byte AES-256 key -> displayed to user as
 *     a 32-character hex-ish "restore key" (the derived key itself, base64/hex encoded).
 *  3. All of /storage/emulated/0/AmyBrain is zipped, then AES-256-CBC encrypted.
 *  4. Restore reverses: key -> decrypt -> unzip back into AmyBrain.
 */
object AmyBackup {

    private const val BRAIN_DIR = "/storage/emulated/0/AmyBrain"
    private const val BACKUP_DIR = "/storage/emulated/0/AmyDownloads/backups"
    private const val AES_TRANSFORM = "AES/CBC/PKCS5Padding"

    private fun deriveKey(passphrase: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(passphrase.toByteArray(Charsets.UTF_8)) // 32 bytes = AES-256
    }

    /** Converts the derived 32-byte key into a 32-character human-shareable string (hex truncated). */
    fun keyToDisplayString(keyBytes: ByteArray): String {
        val hex = keyBytes.joinToString("") { "%02x".format(it) }
        return hex.take(32)
    }

    /** Reconstructs key bytes from the 32-char display string by re-hashing it back to 32 bytes. */
    private fun displayStringToKey(display: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(display.toByteArray(Charsets.UTF_8))
    }

    private fun zipDirectory(sourceDir: File, zipFile: File) {
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            sourceDir.walkTopDown().filter { it.isFile }.forEach { file ->
                val entryName = file.relativeTo(sourceDir).path
                zos.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }

    private fun unzipToDirectory(zipFile: File, targetDir: File) {
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                outFile.parentFile?.mkdirs()
                if (!entry.isDirectory) {
                    outFile.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /**
     * Creates an encrypted backup of AmyBrain. Returns (success, restoreKeyDisplay, filePath).
     */
    suspend fun createBackup(passphrase: String): Triple<Boolean, String, String> = withContext(Dispatchers.IO) {
        try {
            val brainDir = File(BRAIN_DIR)
            if (!brainDir.exists()) brainDir.mkdirs()

            val backupDir = File(BACKUP_DIR)
            if (!backupDir.exists()) backupDir.mkdirs()

            val timestamp = System.currentTimeMillis()
            val tempZip = File(backupDir, "amy_backup_$timestamp.zip")
            zipDirectory(brainDir, tempZip)

            val keyBytes = deriveKey(passphrase)
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val iv = ByteArray(16)
            SecureRandom().nextBytes(iv)
            val ivSpec = IvParameterSpec(iv)

            val cipher = Cipher.getInstance(AES_TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)

            val encryptedFile = File(backupDir, "amy_backup_$timestamp.aes")
            encryptedFile.outputStream().use { out ->
                out.write(iv) // prepend IV so restore can read it back
                tempZip.inputStream().use { input ->
                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    val cipherOut = javax.crypto.CipherOutputStream(out, cipher)
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        cipherOut.write(buffer, 0, bytesRead)
                    }
                    cipherOut.flush()
                    cipherOut.close()
                }
            }
            tempZip.delete()

            val displayKey = keyToDisplayString(keyBytes)
            AmyLogger.i("AmyBackup", "Backup created: ${encryptedFile.absolutePath}")
            Triple(true, displayKey, encryptedFile.absolutePath)
        } catch (ex: Exception) {
            AmyLogger.e("AmyBackup", "Backup failed", ex)
            Triple(false, "", "")
        }
    }

    /**
     * Restores from an encrypted backup file using the 32-char restore key.
     */
    suspend fun restoreBackup(backupFilePath: String, restoreKeyDisplay: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val encryptedFile = File(backupFilePath)
            if (!encryptedFile.exists()) {
                AmyLogger.e("AmyBackup", "Backup file not found: $backupFilePath")
                return@withContext false
            }

            val keyBytes = displayStringToKey(restoreKeyDisplay)
            val secretKey = SecretKeySpec(keyBytes, "AES")

            val inputBytes = encryptedFile.readBytes()
            val iv = inputBytes.copyOfRange(0, 16)
            val cipherText = inputBytes.copyOfRange(16, inputBytes.size)
            val ivSpec = IvParameterSpec(iv)

            val cipher = Cipher.getInstance(AES_TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            val decrypted = cipher.doFinal(cipherText)

            val tempZip = File(BACKUP_DIR, "restore_temp_${System.currentTimeMillis()}.zip")
            tempZip.parentFile?.mkdirs()
            tempZip.writeBytes(decrypted)

            val brainDir = File(BRAIN_DIR)
            if (!brainDir.exists()) brainDir.mkdirs()
            unzipToDirectory(tempZip, brainDir)
            tempZip.delete()

            AmyLogger.i("AmyBackup", "Restore successful from $backupFilePath")
            true
        } catch (ex: Exception) {
            AmyLogger.e("AmyBackup", "Restore failed (likely wrong key or corrupted file)", ex)
            false
        }
    }

    suspend fun listBackups(): List<String> = withContext(Dispatchers.IO) {
        try {
            val dir = File(BACKUP_DIR)
            if (!dir.exists()) return@withContext emptyList()
            dir.listFiles { f -> f.extension == "aes" }?.map { it.absolutePath } ?: emptyList()
        } catch (ex: Exception) {
            AmyLogger.e("AmyBackup", "Failed listing backups", ex)
            emptyList()
        }
    }
}
