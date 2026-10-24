package io.github.linuskromnow.linusexplorer.fs

/** Coarse file category used for icons and, later, filtering. */
enum class FileKind {
    FOLDER, IMAGE, VIDEO, AUDIO, TEXT, PDF, DOCUMENT, ARCHIVE, APK, ENCRYPTED, OTHER;

    companion object {
        fun of(entry: FileEntry): FileKind =
            if (entry.isDirectory) FOLDER else byExtension[entry.extension] ?: OTHER

        private val byExtension: Map<String, FileKind> = buildMap {
            fun add(kind: FileKind, vararg extensions: String) = extensions.forEach { put(it, kind) }
            add(IMAGE, "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "svg", "tif", "tiff", "dng", "raw")
            add(VIDEO, "mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "ts", "wmv", "flv")
            add(AUDIO, "mp3", "flac", "ogg", "opus", "m4a", "aac", "wav", "wma", "amr", "mid", "midi")
            add(TEXT, "txt", "md", "log", "csv", "json", "xml", "yml", "yaml", "ini", "conf", "cfg", "html", "htm", "css", "js", "kt", "java", "py", "sh", "c", "h", "cpp", "hpp", "rs", "go", "kts", "gradle", "properties", "toml", "tsv", "sql", "bat")
            add(PDF, "pdf")
            add(DOCUMENT, "doc", "docx", "odt", "rtf", "xls", "xlsx", "ods", "ppt", "pptx", "odp", "epub")
            add(ARCHIVE, "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst", "lz4", "iso")
            add(APK, "apk", "apks", "xapk", "aab")
            add(ENCRYPTED, "age")
        }
    }
}
