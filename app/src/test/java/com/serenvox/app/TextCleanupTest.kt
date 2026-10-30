// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Ryan Lu
package com.serenvox.app

import com.serenvox.app.doc.OfficeExtractors
import com.serenvox.app.doc.TextCleanup
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TextCleanupTest {
    @Test fun removesHeadersFootersAndPageNumbers() {
        val pages = (1..5).map { "My Book Title\nChapter text on page $it continues\nhere nicely.\n$it" }
        val out = TextCleanup.cleanPages(pages)
        assertFalse(out.contains("My Book Title"))
        assertTrue(out.contains("Chapter text on page 3 continues here nicely."))
        assertFalse(Regex("""(^|\n)\d+(\n|$)""").containsMatchIn(out))
    }

    @Test fun joinsHyphenationAndWraps() {
        assertEquals("An extraordinary day was had.\n\nNew para.", TextCleanup.reflow("An extra-\nordinary day\nwas had.\n\nNew para."))
    }

    @Test fun keepsRealHyphens() { assertEquals("A well-known fact.", TextCleanup.reflow("A well-known fact.")) }

    private fun zip(vararg files: Pair<String, String>) = ByteArrayOutputStream().also { b ->
        ZipOutputStream(b).use { z -> files.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
    }.toByteArray()

    @Test fun docx() {
        val xml = """<w:document><w:body><w:p><w:r><w:t>Hello</w:t></w:r><w:r><w:t xml:space="preserve"> world &amp; co.</w:t></w:r></w:p><w:p><w:r><w:t>Second</w:t></w:r></w:p></w:body></w:document>"""
        assertEquals("Hello world & co.\nSecond", OfficeExtractors.docx(zip("word/document.xml" to xml)))
    }

    @Test fun epubSpineOrder() {
        val bytes = zip(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OEBPS/book.opf"/></rootfiles></container>""",
            "OEBPS/book.opf" to """<package><manifest><item id="b" href="b.xhtml"/><item id="a" href="a.xhtml"/></manifest><spine><itemref idref="a"/><itemref idref="b"/></spine></package>""",
            "OEBPS/a.xhtml" to "<html><head><title>x</title></head><body><h1>One</h1><p>First.</p></body></html>",
            "OEBPS/b.xhtml" to "<html><body><p>Second.</p></body></html>",
        )
        assertEquals("One\nFirst.\n\nSecond.", OfficeExtractors.epub(bytes))
    }

    @Test fun wordControlChars() {
        assertEquals("See page 4 now.\nNext", OfficeExtractors.cleanWordControl("See page \u0013 PAGEREF x \u00144\u0015 now.\rNext"))
    }

    private fun res(n: String) = javaClass.classLoader!!.getResourceAsStream(n)!!.readBytes()

    @Test fun realDocx() {
        val t = OfficeExtractors.docx(res("sample.docx"))
        assertTrue(t, t.startsWith("Sample Title")); assertTrue(t.contains("Paragraph 59.")); assertTrue(t.contains("Café naïve résumé."))
    }

    @Test fun realLegacyDoc() {
        val t = OfficeExtractors.doc(res("sample.doc"))
        assertTrue(t, t.contains("Sample Title")); assertTrue(t, t.contains("Paragraph 59. The quick brown fox")); assertTrue(t.contains("Café naïve résumé."))
    }
}
