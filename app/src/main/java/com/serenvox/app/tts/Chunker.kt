package com.serenvox.app.tts

/** Splits long text into sentence-sized chunks so synthesis can stream and long text never blocks. */
object Chunker {
    private val sentenceEnd = Regex("""(?<=[.!?…;:]["'”’)\]]{0,3})\s+|\n+""")

    fun split(text: String, maxLen: Int = 300): List<String> {
        val out = mutableListOf<String>()
        for (raw in text.split(sentenceEnd)) {
            val s = raw.trim()
            if (s.isEmpty()) continue
            if (s.length <= maxLen) { out += s; continue }
            // Very long sentence: break on commas, then on spaces.
            var cur = StringBuilder()
            for (word in s.split(Regex("""(?<=,)\s+|\s+"""))) {
                if (cur.isNotEmpty() && cur.length + word.length + 1 > maxLen) {
                    out += cur.toString(); cur = StringBuilder()
                }
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(word)
            }
            if (cur.isNotEmpty()) out += cur.toString()
        }
        return out
    }
}
