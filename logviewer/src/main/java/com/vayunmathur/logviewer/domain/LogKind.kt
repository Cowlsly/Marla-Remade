package com.vayunmathur.logviewer.domain

/**
 * Which of the two things is on screen, and the presentation differences that follow from it.
 *
 * A logcat dump is wide, machine-formatted and read from the end, so it starts small and jumps to
 * the newest line. An error report is a stack trace read from the top at a normal size.
 */
enum class LogKind(
    val initialFontSizeSp: Float,
    val scrollToBottom: Boolean,
) {
    Logcat(initialFontSizeSp = 9f, scrollToBottom = true),
    ErrorReport(initialFontSizeSp = 12f, scrollToBottom = false),
    ;

    /**
     * Tabs become spaces because a proportional-to-monospace tab stop lands nowhere useful, and the
     * line is trimmed because logcat pads its columns.
     */
    fun displayLine(line: String): String = when (this) {
        Logcat -> line.replace('\t', ' ').trim()
        ErrorReport -> line
    }

    /** Trimmed but tabs kept: what is copied should still paste as the original text. */
    fun copyLine(line: String): String = when (this) {
        Logcat -> line.trim()
        ErrorReport -> line
    }
}
