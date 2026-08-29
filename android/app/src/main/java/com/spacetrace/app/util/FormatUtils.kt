package com.spacetrace.app.util

import java.text.CharacterIterator
import java.text.StringCharacterIterator
import java.util.Locale
import kotlin.math.abs

object FormatUtils {

    /** e.g. 1536 -> "1.5 KB", matching how Android's own Storage settings formats sizes. */
    fun formatBytes(bytes: Long): String {
        val absBytes = if (bytes == Long.MIN_VALUE) Long.MAX_VALUE else abs(bytes)
        if (absBytes < 1000) return "$bytes B"
        val ci: CharacterIterator = StringCharacterIterator("kMGTPE")
        var value = absBytes
        var remainder = 0L
        while (value >= 1000 && ci.next() != CharacterIterator.DONE) {
            remainder = value % 1000
            value /= 1000
        }
        val sign = if (bytes < 0) "-" else ""
        return String.format(Locale.US, "%s%.1f %cB", sign, value + remainder / 1000.0, ci.current())
    }

    fun formatCount(count: Int): String = String.format(Locale.US, "%,d", count)
}
