// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Ryan Lu
package com.serenvox.app.doc

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ImportedDoc(val title: String, val text: String, val kind: String)

class ImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Turns PDF, Word (.docx/.doc), EPUB, HTML and plain-text files into speakable text, fully offline. */
class DocumentImporter(private val context: Context) {
    companion object {
        private const val TAG = "SerenvoxImport"
        const val BLANK_PDF = "This PDF appears to be blank, with no text or images. Try re-saving or re-exporting it."
        const val MAX_OCR_PAGES = 300
        const val MAX_BYTES = 60L shl 20
        val MIME_TYPES = arrayOf(
            "application/pdf", "text/plain", "text/html", "application/epub+zip", "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/octet-stream",
        )
    }

    private var pdfReady = false

    suspend fun import(uri: Uri, mimeHint: String? = null, onProgress: (String) -> Unit = {}): ImportedDoc = withContext(Dispatchers.IO) {
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
                "pdf" -> pdf(bytes, onProgress)
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
            Log.e(TAG, "Import failed ($kind, $name)", e)
            throw ImportException("Couldn't read this file (${e.javaClass.simpleName}: ${e.message ?: "no details"}).", e)
        }
        if (text.isBlank()) throw ImportException(
            if (kind == "pdf") "No text found in this PDF, even with text recognition (OCR). The pages may be blank or the scan too faint."
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

    private suspend fun pdf(bytes: ByteArray, onProgress: (String) -> Unit): String {
        onProgress("Reading PDF…")
        var hasImages = true
        val extracted: String? = try {
            if (!pdfReady) { PDFBoxResourceLoader.init(context.applicationContext); pdfReady = true }
            PDDocument.load(bytes).use { d ->
                val pages = (1..d.numberOfPages).map { p ->
                    onProgress("Reading page $p of ${d.numberOfPages}…")
                    try {
                        PDFTextStripper().apply { sortByPosition = true; startPage = p; endPage = p }.getText(d)
                    } catch (e: Exception) { Log.w(TAG, "Text extraction failed on page $p", e); "" }
                }
                hasImages = d.pages.any { pg -> runCatching { pg.resources?.xObjectNames?.any() == true }.getOrDefault(true) }
                Log.i(TAG, "PdfBox extracted ${pages.sumOf { it.trim().length }} chars from ${pages.size} pages")
                TextCleanup.cleanPages(pages)
            }
        } catch (e: InvalidPasswordException) {
            throw ImportException("This PDF is password-protected. Remove the password and import it again.", e)
        } catch (e: Exception) {
            // Don't hide the failure: log it and try the platform renderer + OCR instead.
            Log.e(TAG, "PdfBox failed to parse the PDF", e); null
        }
        // Treat almost-empty output (e.g. only page numbers) as "no text layer".
        if (extracted != null && extracted.count { it.isLetterOrDigit() } >= 20) return extracted
        if (extracted != null && !hasImages) throw ImportException(BLANK_PDF)
        val ocr = try {
            PdfOcr.recognise(context, bytes, MAX_OCR_PAGES) { page, total -> onProgress("Recognising text (OCR): page $page of $total…") }
        } catch (e: ImportException) { throw e } catch (e: Exception) {
            Log.e(TAG, "OCR failed", e)
            throw ImportException("Couldn't read this PDF (${e.javaClass.simpleName}: ${e.message ?: "no details"}).", e)
        }
        if (ocr.isBlank()) throw ImportException(BLANK_PDF)
        return ocr
    }
}
