package io.github.linuskromnow.linusexplorer.fs

enum class SortField { NAME, SIZE, DATE, TYPE }

data class BrowserPrefs(
    val sortField: SortField = SortField.NAME,
    val descending: Boolean = false,
    val foldersFirst: Boolean = true,
    val showHidden: Boolean = false,
    val showThumbnails: Boolean = true,
)

object FileSorting {
    fun sort(entries: List<FileEntry>, prefs: BrowserPrefs): List<FileEntry> {
        val byName = Comparator<FileEntry> { a, b -> NaturalOrderComparator.compare(a.name, b.name) }
        val byField = when (prefs.sortField) {
            SortField.NAME -> byName
            SortField.SIZE -> compareBy<FileEntry> { it.size }.then(byName)
            SortField.DATE -> compareBy<FileEntry> { it.lastModified }.then(byName)
            SortField.TYPE -> compareBy<FileEntry> { it.extension }.then(byName)
        }
        val directed = if (prefs.descending) byField.reversed() else byField
        val comparator = if (prefs.foldersFirst) compareBy<FileEntry> { !it.isDirectory }.then(directed) else directed
        return entries.filter { prefs.showHidden || !it.isHidden }.sortedWith(comparator)
    }
}

/** Case-insensitive ordering that treats digit runs as numbers: "file2" < "file10". */
object NaturalOrderComparator : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isAsciiDigit() && b[j].isAsciiDigit()) {
                val startA = i
                while (i < a.length && a[i].isAsciiDigit()) i++
                val startB = j
                while (j < b.length && b[j].isAsciiDigit()) j++
                val numA = a.substring(startA, i).trimStart('0')
                val numB = b.substring(startB, j).trimStart('0')
                if (numA.length != numB.length) return numA.length - numB.length
                val cmp = numA.compareTo(numB)
                if (cmp != 0) return cmp
            } else {
                val cmp = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        val remaining = (a.length - i) - (b.length - j)
        return if (remaining != 0) remaining else a.compareTo(b)
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'
}
