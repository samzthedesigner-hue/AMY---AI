package com.amy.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/**
 * AmyDocuments - Group 5: Open, Create PDF/DOCX/Sheet, Summarize PDF, Encrypt, Zip, Unzip
 * (Features 27-36)
 *
 * PDF/DOCX/Sheet creation here uses lightweight manual format writers (minimal valid PDF,
 * minimal valid DOCX-as-zip-of-XML, minimal CSV-as-sheet) rather than heavy third-party
 * libraries, to respect the dependency whitelist (androidx, material, coroutines, exoplayer,
 * tflite, jsoup, okhttp only). For richer DOCX/Sheet output, CLOUDCONVERT_KEY can be wired
 * in AmyKeys and used via OkHttp if you want full-fidelity Office formats later.
 */
object AmyDocuments {

    private const val DOCS_DIR = "/storage/emulated/0/AmyBrain/documents"

    private fun ensureDir(): File {
        val dir = File(DOCS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // Feature 27: Open
    fun openDocument(context: Context, filePath: String): Boolean {
        return try {
            val file = File(filePath)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, guessMimeType(filePath))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (ex: Exception) {
            AmyLogger.e("AmyDocuments", "openDocument failed", ex)
            false
        }
    }

    private fun guessMimeType(path: String): String = when (path.substringAfterLast('.').lowercase()) {
        "pdf" -> "application/pdf"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "csv" -> "text/csv"
        "zip" -> "application/zip"
        else -> "*/*"
    }

    // Feature 28: Create PDF (minimal valid single/multi-page text PDF, no external lib)
    suspend fun createPdf(title: String, bodyText: String): String = withContext(Dispatchers.IO) {
        val dir = ensureDir()
        val file = File(dir, "${sanitize(title)}.pdf")
        try {
            val content = buildMinimalPdf(title, bodyText)
            file.writeBytes(content)
            AmyLogger.i("AmyDocuments", "PDF created: ${file.absolutePath}")
            file.absolutePath
        } catch (ex: Exception) {
            AmyLogger.e("AmyDocuments", "createPdf failed", ex)
            ""
        }
    }

    private fun buildMinimalPdf(title: String, body: String): ByteArray {
        // A minimal, valid single-page PDF with plain text content. No external PDF lib needed.
        val escapedBody = body.replace("(", "\\(").replace(")", "\\)").replace("\n", ") Tj T* (")
        val streamContent = "BT /F1 12 Tf 40 750 Td ($escapedBody) Tj ET"
        val objects = mutableListOf<String>()
        objects.add("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        objects.add("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        objects.add(
            "3 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << /Font << /F1 5 0 R >> >> " +
                "/MediaBox [0 0 612 792] /Contents 4 0 R >>\nendobj\n"
        )
        objects.add("4 0 obj\n<< /Length ${streamContent.length} >>\nstream\n$streamContent\nendstream\nendobj\n")
        objects.add("5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n")

        val sb = StringBuilder()
        sb.append("%PDF-1.4\n")
        val offsets = mutableListOf<Int>()
        for (obj in objects) {
            offsets.add(sb.length)
            sb.append(obj)
        }
        val xrefStart = sb.length
        sb.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) {
            sb.append("%010d 00000 n \n".format(offset))
        }
        sb.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xrefStart\n%%EOF")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    // Feature 29: Create DOCX (minimal valid docx = zip of Office Open XML)
    suspend fun createDocx(title: String, bodyText: String): String = withContext(Dispatchers.IO) {
        val dir = ensureDir()
        val file = File(dir, "${sanitize(title)}.docx")
        try {
            ZipOutputStream(file.outputStream()).use { zos ->
                zos.putNextEntry(ZipEntry("[Content_Types].xml"))
                zos.write(DOCX_CONTENT_TYPES.toByteArray())
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("_rels/.rels"))
                zos.write(DOCX_RELS.toByteArray())
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("word/document.xml"))
                val escaped = bodyText.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                val paragraphs = escaped.split("\n").joinToString("") { "<w:p><w:r><w:t xml:space=\"preserve\">$it</w:t></w:r></w:p>" }
                zos.write(DOCX_DOCUMENT_TEMPLATE.replace("{{BODY}}", paragraphs).toByteArray())
                zos.closeEntry()
            }
            AmyLogger.i("AmyDocuments", "DOCX created: ${file.absolutePath}")
            file.absolutePath
        } catch (ex: Exception) {
            AmyLogger.e("AmyDocuments", "createDocx failed", ex)
            ""
        }
    }

    // Feature 30: Create Sheet (CSV, opens in any spreadsheet app)
    suspend fun createSheet(title: String, rows: List<List<String>>): String = withContext(Dispatchers.IO) {
        val dir = ensureDir()
        val file = File(dir, "${sanitize(title)}.csv")
        try {
            val sb = StringBuilder()
            rows.forEach { row -> sb.append(row.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" }).append("\n") }
            file.writeText(sb.toString())
            AmyLogger.i("AmyDocuments", "Sheet created: ${file.absolutePath}")
            file.absolutePath
        } catch (ex: Exception) {
            AmyLogger.e("AmyDocuments", "createSheet failed", ex)
            ""
        }
    }

    // Feature 31: Summarize PDF (extracts raw text streams heuristically, then truncates/condenses)
    suspend fun summarizePdf(pdfPath: String): String = withContext(Dispatchers.IO) {
        try {
            val file = File(pdfPath)
            if (!file.exists()) return@withContext "PDF not found."
            val raw = file.readText(Charsets.ISO_8859_1)
            // crude extraction: pull text between "BT" and "ET" markers
            val regex = Regex("BT(.*?)ET", RegexOption.DOT_MATCHES_ALL)
            val matches = regex.findAll(raw).map { it.groupValues[1] }.joinToString(" ")
            val textOnly = Regex("\\((.*?)\\)").findAll(matches).map { it.groupValues[1] }.joinToString(" ")
            if (textOnly.isBlank()) "Could not extract readable text from this PDF."
            else "Summary (first 500 chars): " + textOnly.take(500)
        } catch (ex: Exception) {
            AmyLogger.e("AmyDocuments", "summarizePdf failed", ex)
            "PDF summarization failed."
        }
    }

    // Feature 32: Encrypt (AES-256-CBC, single file)
    suspend fun encryptFile(filePath: String, passphrase: String): String = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (!file.exists()) return@withContext ""
            val keyBytes = MessageDigest.getInstance("SHA-256").digest(passphrase.toByteArray(Charsets.UTF_8))
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val iv = ByteArray(16)
            SecureRandom().nextBytes(iv)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(iv))
            val outFile = File(file.parentFile, "${file.name}.enc")
            outFile.outputStream().use { out ->
                out.write(iv)
                javax.crypto.CipherOutputStream(out, cipher).use { cipherOut ->
                    file.inputStream().use { it.copyTo(cipherOut) }
                }
            }
            AmyLogger.i("AmyDocuments", "Encrypted: ${outFile.absolutePath}")
            outFile.absolutePath
        } catch (ex: Exception) {
            AmyLogger.e("AmyDocuments", "encryptFile failed", ex)
            ""
        }
    }

    // Feature 33: Zip
    suspend fun zipFiles(filePaths: List<String>, outputName: String): String = withContext(Dispatchers.IO) {
        val dir = ensureDir()
        val zipFile = File(dir, "${sanitize(outputName)}.zip")
        try {
            ZipOutputStream(zipFile.outputStream()).use { zos ->
                for (path in filePaths) {
                    val file = File(path)
                    if (!file.exists()) continue
                    zos.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            AmyLogger.i("AmyDocuments", "Zipped to ${zipFile.absolutePath}")
            zipFile.absolutePath
        } catch (ex: Exception) {
            AmyLogger.e("AmyDocuments", "zipFiles failed", ex)
            ""
        }
    }

    // Feature 34 (Unzip): extracts to a folder next to the zip
    suspend fun unzipFile(zipPath: String): String = withContext(Dispatchers.IO) {
        try {
            val zipFile = File(zipPath)
            if (!zipFile.exists()) return@withContext ""
            val targetDir = File(zipFile.parentFile, zipFile.nameWithoutExtension + "_extracted")
            if (!targetDir.exists()) targetDir.mkdirs()
            ZipFile(zipFile).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val outFile = File(targetDir, entry.name)
                    outFile.parentFile?.mkdirs()
                    if (!entry.isDirectory) {
                        zf.getInputStream(entry).use { input ->
                            outFile.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
            }
            AmyLogger.i("AmyDocuments", "Unzipped to ${targetDir.absolutePath}")
            targetDir.absolutePath
        } catch (ex: Exception) {
            AmyLogger.e("AmyDocuments", "unzipFile failed", ex)
            ""
        }
    }

    private fun sanitize(name: String): String = name.replace(Regex("[^a-zA-Z0-9_-]"), "_")

    private const val DOCX_CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>"""

    private const val DOCX_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

    private const val DOCX_DOCUMENT_TEMPLATE = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:body>{{BODY}}</w:body>
</w:document>"""
}
