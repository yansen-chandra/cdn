package com.spacetrace.app.util

import java.text.StringCharacterIterator
import java.util.Locale

object FormatUtils {

    /** e.g. 1536 -> "1.5 kB", matching how Android's own Storage settings formats sizes. */
    fun formatBytes(bytesIn: Long): String {
        var bytes = bytesIn
        if (bytes > -1000 && bytes < 1000) return "$bytes B"

        val ci = StringCharacterIterator("kMGTPE")
        while (bytes <= -999_950 || bytes >= 999_950) {
            bytes /= 1000
            ci.next()
        }
        return String.format(Locale.US, "%.1f %cB", bytes / 1000.0, ci.current())
    }

    fun formatCount(count: Int): String = String.format(Locale.US, "%,d", count)
}
