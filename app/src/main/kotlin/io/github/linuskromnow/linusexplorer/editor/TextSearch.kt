package io.github.linuskromnow.linusexplorer.editor

/** A match in the text; [end] is exclusive. */
data class Match(val start: Int, val end: Int)

data class ReplaceResult(val text: String, val count: Int)

/** Find and replace on plain strings. An empty query never matches. */
object TextSearch {
    /** First match starting at or after [from], wrapping around to the top; null if there is none. */
    fun findNext(text: String, query: String, from: Int, caseSensitive: Boolean): Match? {
        if (query.isEmpty()) return null
        val start = from.coerceIn(0, text.length)
        val index = text.indexOf(query, start, ignoreCase = !caseSensitive)
            .takeIf { it >= 0 }
            ?: text.indexOf(query, 0, ignoreCase = !caseSensitive).takeIf { it >= 0 }
            ?: return null
        return Match(index, index + query.length)
    }

    /** Last match that ends at or before [before], wrapping around to the bottom; null if there is none. */
    fun findPrevious(text: String, query: String, before: Int, caseSensitive: Boolean): Match? {
        if (query.isEmpty()) return null
        val latestStart = before.coerceIn(0, text.length) - query.length
        val index = (if (latestStart >= 0) text.lastIndexOf(query, latestStart, ignoreCase = !caseSensitive) else -1)
            .takeIf { it >= 0 }
            ?: text.lastIndexOf(query, text.length, ignoreCase = !caseSensitive).takeIf { it >= 0 }
            ?: return null
        return Match(index, index + query.length)
    }

    fun count(text: String, query: String, caseSensitive: Boolean): Int {
        if (query.isEmpty()) return 0
        var count = 0
        var index = text.indexOf(query, 0, ignoreCase = !caseSensitive)
        while (index >= 0) {
            count++
            index = text.indexOf(query, index + query.length, ignoreCase = !caseSensitive)
        }
        return count
    }

    /** Whether [range] of [text] is exactly an occurrence of [query]. */
    fun matchesAt(text: String, query: String, range: Match, caseSensitive: Boolean): Boolean =
        query.isNotEmpty() && range.start >= 0 && range.end <= text.length &&
            range.end - range.start == query.length &&
            text.regionMatches(range.start, query, 0, query.length, ignoreCase = !caseSensitive)

    fun replaceAll(text: String, query: String, replacement: String, caseSensitive: Boolean): ReplaceResult {
        if (query.isEmpty()) return ReplaceResult(text, 0)
        var index = text.indexOf(query, 0, ignoreCase = !caseSensitive)
        if (index < 0) return ReplaceResult(text, 0)
        val out = StringBuilder(text.length)
        var last = 0
        var count = 0
        while (index >= 0) {
            out.append(text, last, index).append(replacement)
            last = index + query.length
            count++
            index = text.indexOf(query, last, ignoreCase = !caseSensitive)
        }
        out.append(text, last, text.length)
        return ReplaceResult(out.toString(), count)
    }
}
