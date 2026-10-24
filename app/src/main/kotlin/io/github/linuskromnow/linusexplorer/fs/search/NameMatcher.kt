package io.github.linuskromnow.linusexplorer.fs.search

/**
 * Case-insensitive file name matching for the search.
 *
 * A plain query is a substring match ("report" finds "Annual Report.pdf"). If it contains `*` (any run of
 * characters) or `?` (exactly one character) it is a wildcard pattern that must match the whole name,
 * so "*.jpg" finds only JPEG files and "img_*" only names that start with "img_".
 */
class NameMatcher private constructor(private val pattern: String, private val wildcard: Boolean) {
    fun matches(name: String): Boolean {
        val lower = name.lowercase()
        return if (wildcard) globMatches(pattern, lower) else lower.contains(pattern)
    }

    companion object {
        /** Returns null for a blank query, which matches nothing useful. */
        fun parse(query: String): NameMatcher? {
            val trimmed = query.trim().lowercase()
            if (trimmed.isEmpty()) return null
            return NameMatcher(trimmed, wildcard = '*' in trimmed || '?' in trimmed)
        }

        /** Linear-time glob matching with backtracking only to the most recent `*`. */
        private fun globMatches(pattern: String, text: String): Boolean {
            var p = 0
            var t = 0
            var starP = -1
            var starT = 0
            while (t < text.length) {
                when {
                    p < pattern.length && (pattern[p] == '?' || pattern[p] == text[t]) && pattern[p] != '*' -> {
                        p++
                        t++
                    }
                    p < pattern.length && pattern[p] == '*' -> {
                        starP = p++
                        starT = t
                    }
                    starP >= 0 -> {
                        p = starP + 1
                        t = ++starT
                    }
                    else -> return false
                }
            }
            while (p < pattern.length && pattern[p] == '*') p++
            return p == pattern.length
        }
    }
}
