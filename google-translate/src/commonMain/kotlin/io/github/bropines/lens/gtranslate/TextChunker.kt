package io.github.bropines.lens.gtranslate

/**
 * Splits long text into chunks Google accepts, cutting where a sentence or
 * line ends. Each chunk remembers the whitespace around it, because Google
 * trims it from every translated chunk and the join must put it back.
 */
internal object TextChunker {
    const val WINDOW = 10_000
    const val MAX_CHUNKS = 5
    const val MAX_TOTAL = WINDOW * MAX_CHUNKS

    data class Chunk(val leading: String, val body: String, val trailing: String)

    fun split(text: String): List<Chunk> {
        if (text.length > MAX_TOTAL) throw GoogleTranslateException.TooLong(text.length)
        val pieces = mutableListOf<String>()
        var start = 0
        while (text.length - start > WINDOW) {
            val end = cut(text, start, start + WINDOW)
            pieces += text.substring(start, end)
            start = end
        }
        pieces += text.substring(start)
        return pieces.filter { it.isNotEmpty() }.map { piece ->
            val lead = piece.takeWhile { it.isWhitespace() }
            val rest = piece.substring(lead.length)
            val trail = rest.takeLastWhile { it.isWhitespace() }
            Chunk(lead, rest.substring(0, rest.length - trail.length), trail)
        }
    }

    /** Where to end a chunk that may not go past [limit], preferring natural boundaries. */
    private fun cut(text: String, start: Int, limit: Int): Int {
        val half = start + (limit - start) / 2
        // 1. After a newline in the second half of the window.
        text.lastIndexOf('\n', limit - 1).let { if (it >= half) return it + 1 }
        // 2. After the whitespace following . ! ?, or straight after 。！？.
        for (i in limit - 1 downTo start + 1) {
            val c = text[i]
            if (c in "。！？") return i + 1
            if (c.isWhitespace() && text[i - 1] in ".!?") return i + 1
        }
        // 3. After any whitespace.
        for (i in limit - 1 downTo start + 1) if (text[i].isWhitespace()) return i + 1
        // 4. At the limit.
        return limit
    }
}
