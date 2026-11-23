package io.github.bropines.lens.translate

/**
 * Splits long text into pieces an engine accepts, cutting where a line or a
 * sentence ends. Each chunk remembers the whitespace around it: engines trim
 * it from a translation, and the join must put it back.
 */
public object TextChunker {
    public data class Chunk(val leading: String, val body: String, val trailing: String)

    /** Chunks of at most [window] characters whose concatenation is exactly [text]. */
    public fun split(text: String, window: Int): List<Chunk> {
        require(window > 0) { "window must be positive" }
        val pieces = mutableListOf<String>()
        var start = 0
        while (text.length - start > window) {
            val end = cut(text, start, start + window)
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
        // 4. At the limit, but never between the two halves of a surrogate pair.
        return if (text[limit - 1].isHighSurrogate() && limit - 1 > start) limit - 1 else limit
    }
}
