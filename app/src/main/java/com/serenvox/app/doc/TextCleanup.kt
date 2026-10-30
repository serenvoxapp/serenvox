// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Ryan Lu
package com.serenvox.app.doc

/** Cleans text extracted from paginated documents so it reads naturally aloud. */
object TextCleanup {
    private val pageNumber = Regex("""^\s*(page\s*)?[-–—]?\s*\d{1,4}\s*([-–—]|(of|/)\s*\d{1,4})?\s*$""", RegexOption.IGNORE_CASE)

    private fun norm(line: String) = line.trim().lowercase().replace(Regex("""^\d+\s*|\s*\d+$"""), "#")

    private fun e(n: Int) = if (n > 6) 2 else 1

    /** Removes running headers/footers and page numbers, then reflows. */
    fun cleanPages(pages: List<String>): String {
        val pageLines = pages.map { p -> p.lines().map { it.trimEnd() } }
        val counts = HashMap<String, Int>()
        for (lines in pageLines) {
            val ne = lines.filter { it.isNotBlank() }
            (ne.take(e(ne.size)) + ne.takeLast(e(ne.size))).map(::norm).toSet().forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        val threshold = maxOf(3, (pages.size * 0.4).toInt())
        val repeated = counts.filter { it.value >= threshold && it.key.length < 100 }.keys
        val body = pageLines.joinToString("\n\n") { lines ->
            val ne = lines.withIndex().filter { it.value.isNotBlank() }
            val edge = (ne.take(e(ne.size)) + ne.takeLast(e(ne.size))).map { it.index }.toSet()
            lines.filterIndexed { i, l ->
                !(pageNumber.matches(l) || (i in edge && norm(l) in repeated))
            }.joinToString("\n")
        }
        return reflow(body)
    }

    /** Joins hyphenated words and hard-wrapped lines; keeps paragraph breaks. */
    fun reflow(text: String): String {
        var t = text.replace("\r\n", "\n").replace('\r', '\n').replace('\u00A0', ' ')
        t = t.replace(Regex("""(\p{L})[-\u00AD]\n\s*(\p{Ll})"""), "$1$2")       // hyphenation
        t = t.replace("\u00AD", "")
        t = t.replace(Regex("""[ \t]+\n"""), "\n")
        t = t.replace(Regex("""(?<=[^\n.!?:;"”'’)])\n(?=[^\n])"""), " ")      // soft line wraps
        t = t.replace(Regex("""\n{3,}"""), "\n\n")
        t = t.replace(Regex("""[ \t]{2,}"""), " ")
        return t.trim()
    }

    /** Strips XML/HTML tags turning block elements into paragraph breaks. */
    fun xmlToText(xml: String, paragraphTags: String = "p|h[1-6]|li|div|br|tr|w:p|w:br|w:cr"): String {
        var t = xml.replace(Regex("""<(script|style|head)[\s\S]*?</\1>""", RegexOption.IGNORE_CASE), " ")
        t = t.replace(Regex("""<w:tab/>"""), "\t")
        t = t.replace(Regex("""<(/($paragraphTags)|(w:br|w:cr|br)\b[^>]*)(\s[^>]*)?/?>""", RegexOption.IGNORE_CASE), "\n")
        t = t.replace(Regex("""<[^>]+>"""), "")
        t = decodeEntities(t)
        return t.lines().joinToString("\n") { it.trim() }.replace(Regex("""\n{3,}"""), "\n\n").trim()
    }

    fun decodeEntities(s: String): String = s
        .replace(Regex("""&#x([0-9a-fA-F]+);""")) { runCatching { String(Character.toChars(it.groupValues[1].toInt(16))) }.getOrDefault("") }
        .replace(Regex("""&#(\d+);""")) { runCatching { String(Character.toChars(it.groupValues[1].toInt())) }.getOrDefault("") }
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&rsquo;", "’").replace("&lsquo;", "‘").replace("&ldquo;", "“")
        .replace("&rdquo;", "”").replace("&mdash;", "—").replace("&ndash;", "–").replace("&hellip;", "…")
        .replace("&amp;", "&")
}
