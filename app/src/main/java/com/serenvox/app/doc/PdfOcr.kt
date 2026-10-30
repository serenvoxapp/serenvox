// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Ryan Lu
package com.serenvox.app.doc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** On-device OCR for scanned PDFs: Android PdfRenderer + ML Kit Text Recognition (bundled Latin model, offline). */
object PdfOcr {
    suspend fun recognise(context: Context, bytes: ByteArray, maxPages: Int, onPage: (Int, Int) -> Unit): String {
        val tmp = File.createTempFile("ocr", ".pdf", context.cacheDir)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            tmp.writeBytes(bytes)
            val pfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try { PdfRenderer(pfd) } catch (e: SecurityException) {
                pfd.close(); throw ImportException("This PDF is password-protected. Remove the password and import it again.", e)
            }
            renderer.use { r ->
                val total = minOf(r.pageCount, maxPages)
                val pages = ArrayList<String>(total)
                for (i in 0 until total) {
                    coroutineContext.ensureActive()
                    onPage(i + 1, total)
                    val bmp = r.openPage(i).use { page ->
                        // ~200 dpi, capped so big pages don't exhaust memory.
                        val scale = minOf(200f / 72f, 2400f / maxOf(page.width, page.height))
                        val w = (page.width * scale).toInt().coerceAtLeast(1); val h = (page.height * scale).toInt().coerceAtLeast(1)
                        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                            it.eraseColor(Color.WHITE)
                            page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                    try { pages += recognizer.recognize(bmp) } finally { bmp.recycle() }
                }
                return TextCleanup.cleanPages(pages)
            }
        } finally { recognizer.close(); tmp.delete() }
    }

    private suspend fun com.google.mlkit.vision.text.TextRecognizer.recognize(bmp: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { t -> cont.resume(t.textBlocks.joinToString("\n\n") { it.text }) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
}
