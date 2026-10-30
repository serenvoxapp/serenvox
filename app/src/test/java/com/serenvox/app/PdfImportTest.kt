// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Ryan Lu
package com.serenvox.app

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.serenvox.app.doc.DocumentImporter
import com.serenvox.app.doc.ImportException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PdfImportTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun fileUri(name: String, bytes: ByteArray) = Uri.fromFile(File(ctx.cacheDir, name).apply { writeBytes(bytes) })

    @Test fun importsPdf() = runBlocking {
        val bytes = javaClass.classLoader!!.getResourceAsStream("sample.pdf")!!.readBytes()
        val d = DocumentImporter(ctx).import(fileUri("sample.pdf", bytes))
        assertEquals("pdf", d.kind)
        assertTrue(d.text, d.text.contains("Paragraph 59. The quick brown fox jumps over the lazy dog"))
        assertFalse(Regex("""\n\d+\n""").containsMatchIn(d.text))
    }

    @Test fun importsDoc() = runBlocking {
        val bytes = javaClass.classLoader!!.getResourceAsStream("sample.doc")!!.readBytes()
        assertEquals("doc", DocumentImporter(ctx).import(fileUri("x.doc", bytes)).kind)
    }

    @Test fun emptyPdfSaysNoText() = runBlocking {
        // A valid one-page PDF without any text (like a scan without OCR).
        val pdf = "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n" +
            "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF"
        val e = runCatching { DocumentImporter(ctx).import(fileUri("scan.pdf", pdf.toByteArray())) }.exceptionOrNull()
        assertTrue(e is ImportException); assertEquals(DocumentImporter.BLANK_PDF, e!!.message)
    }

    /** The real file from a phone test: Chromium "Save as PDF" of an empty page. */
    @Test fun chromiumBlankPdfSaysBlank() = runBlocking {
        val bytes = javaClass.classLoader!!.getResourceAsStream("blank-chromium.pdf").readBytes()
        val e = runCatching { DocumentImporter(ctx).import(fileUri("user-test.pdf", bytes)) }.exceptionOrNull()
        assertTrue(e is ImportException); assertEquals(DocumentImporter.BLANK_PDF, e!!.message)
    }
}
