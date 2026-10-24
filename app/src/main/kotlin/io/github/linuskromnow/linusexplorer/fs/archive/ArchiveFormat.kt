package io.github.linuskromnow.linusexplorer.fs.archive

/** Archive and compression formats LinusExplorer can open, and which of them it can create. */
enum class ArchiveFormat(
    val label: String,
    /** File name suffixes; the first one is used when creating an archive. */
    val extensions: List<String>,
    val canCreate: Boolean,
    /** Whether LinusExplorer can create password-protected archives of this format. */
    val canEncrypt: Boolean = false,
) {
    ZIP("ZIP", listOf(".zip"), canCreate = true, canEncrypt = true),
    SEVEN_Z("7z", listOf(".7z"), canCreate = true),
    TAR("tar", listOf(".tar"), canCreate = true),
    TAR_GZ("tar.gz", listOf(".tar.gz", ".tgz"), canCreate = true),
    TAR_BZ2("tar.bz2", listOf(".tar.bz2", ".tbz2", ".tbz"), canCreate = true),
    TAR_XZ("tar.xz", listOf(".tar.xz", ".txz"), canCreate = true),
    GZIP("gz", listOf(".gz"), canCreate = false),
    BZIP2("bz2", listOf(".bz2"), canCreate = false),
    XZ("xz", listOf(".xz"), canCreate = false);

    val extension: String get() = extensions.first()

    companion object {
        val creatable: List<ArchiveFormat> = entries.filter { it.canCreate }

        // Longest suffix first so "x.tar.gz" is TAR_GZ rather than GZIP.
        private val bySuffix = entries
            .flatMap { format -> format.extensions.map { it to format } }
            .sortedByDescending { it.first.length }

        fun detect(fileName: String): ArchiveFormat? {
            val lower = fileName.lowercase()
            return bySuffix.firstOrNull { (suffix, _) -> lower.endsWith(suffix) && lower.length > suffix.length }?.second
        }

        /** "backup.tar.gz" -> "backup"; used for the folder an archive is extracted into. */
        fun baseName(fileName: String): String {
            val lower = fileName.lowercase()
            val suffix = bySuffix.firstOrNull { (suffix, _) -> lower.endsWith(suffix) && lower.length > suffix.length }?.first
            return if (suffix == null) fileName else fileName.dropLast(suffix.length)
        }
    }
}
