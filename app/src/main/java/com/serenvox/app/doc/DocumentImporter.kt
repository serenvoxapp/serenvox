// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Ryan Lu
package com.serenvox.app.doc

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ImportedDoc(val title: String, val text: String, val kind: String)

class ImportException(message: String) : Exception(message)

/** Turns PDF, Word (.docx/.doc), EPUB, HTML and plain-text files into speakable text, fully offline. */
class DocumentImporter(private val context: Context) {
    companion object {
        const val MAX_BYTES = 60L shl 20
        val MIME_TYPES = arrayOf(
            "application/pdf", "text/plain", "text/html", "application/epub+zip", "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/octet-stream",
        )
    }

    private var pdfReady = false

    suspend fun import(uri: Uri, mimeHint: String? = null): ImportedDoc = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        var name = "Document"; var size = -1L
        runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) { c.getString(0)?.let { name = it }; if (!c.isNull(1)) size = c.getLong(1) }
            }
        }
        if (size > MAX_BYTES) throw ImportException("This file is too large (max 60 MB).")
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: throw ImportException("Couldn't open the file.")
        val mime = mimeHint ?: cr.getType(uri)
        val kind = detect(bytes, name, mime)
        val title = name.substringBeforeLast('.').ifBlank { "Document" }
        val text = try {
            when (kind) {
                "pdf" -> pdf(bytes)
                "docx" -> OfficeExtractors.docx(bytes)
                "doc" -> try { OfficeExtractors.doc(bytes) } catch (e: Exception) {
                    throw ImportException("This older Word (.doc) file couldn't be read. Open it in Word or Google Docs and save it as .docx, then import it again.")
                }
                "epub" -> OfficeExtractors.epub(bytes)
                "html" -> TextCleanup.xmlToText(bytes.toString(Charsets.UTF_8))
                else -> TextCleanup.reflow(bytes.toString(Charsets.UTF_8))
            }
        } catch (e: ImportException) { throw e } catch (e: OutOfMemoryError) {
            throw ImportException("This file is too large to open on this device.")
        } catch (e: Exception) {
            throw ImportException("Couldn't read this file: ${e.message ?: e.javaClass.simpleName}")
        }
        if (text.isBlank()) throw ImportException(
            if (kind == "pdf") "No text found. This PDF looks like scanned images; Serenvox can only read PDFs that contain real text."
            else "No readable text found in this file.")
        ImportedDoc(title, text, kind)
    }

    private fun detect(b: ByteArray, name: String, mime: String?): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        fun starts(vararg sig: Int) = b.size >= sig.size && sig.indices.all { (b[it].toInt() and 0xff) == sig[it] }
        return when {
            starts(0x25, 0x50, 0x44, 0x46) -> "pdf"                                   // %PDF
            starts(0xD0, 0xCF, 0x11, 0xE0) -> "doc"
            starts(0x50, 0x4B) -> when {
                ext == "epub" || mime == "application/epub+zip" || String(b, 0, minOf(b.size, 100), Charsets.ISO_8859_1).contains("epub") -> "epub"
                else -> "docx"
            }
            ext in setOf("html", "htm", "xhtml") || mime == "text/html" -> "html"
            ext == "rtf" -> throw ImportException("RTF isn't supported yet. Save it as .docx or .txt and try again.")
            else -> "txt"
        }
    }

    private fun pdf(bytes: ByteArray): String {
        if (!pdfReady) { PDFBoxResourceLoader.init(context.applicationContext); pdfReady = true }
        val doc = try { PDDocument.load(bytes) } catch (e: InvalidPasswordException) {
            throw ImportException("This PDF is password-protected.")
        }
        return doc.use { d ->
            val stripper = PDFTextStripper().apply { sortByPosition = true }
            val pages = (1..d.numberOfPages).map { p ->
                stripper.startPage = p; stripper.endPage = p
                stripper.getText(d)
            }
            TextCleanup.cleanPages(pages)
        }
    }
}
