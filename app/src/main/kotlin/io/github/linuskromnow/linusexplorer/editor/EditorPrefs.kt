package io.github.linuskromnow.linusexplorer.editor

data class EditorPrefs(
    val wordWrap: Boolean = false,
    val fontSizeSp: Int = DEFAULT_FONT_SIZE,
) {
    companion object {
        const val MIN_FONT_SIZE = 8
        const val MAX_FONT_SIZE = 32
        const val DEFAULT_FONT_SIZE = 14
    }
}
