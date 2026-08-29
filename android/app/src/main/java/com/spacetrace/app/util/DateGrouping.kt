package com.spacetrace.app.util

import com.spacetrace.app.model.FileEntry
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class DayGroup(val dayKey: String, val label: String, val files: List<FileEntry>)

object DateGrouping {

    private val dayKeyFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val sameYearFormat = SimpleDateFormat("EEEE, MMM d", Locale.US)
    private val otherYearFormat = SimpleDateFormat("EEEE, MMM d, yyyy", Locale.US)

    /** A sortable key identifying the calendar day (device-local timezone) a timestamp falls on. */
    fun dayKey(epochMillis: Long): String = dayKeyFormat.format(epochMillis)

    /** Human label for a calendar day, e.g. "Today", "Yesterday", "Wednesday, Aug 26". */
    fun dayLabel(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val target = Calendar.getInstance().apply { timeInMillis = epochMillis }

        val diffDays = daysBetween(target, today)
        return when (diffDays) {
            0 -> "Today"
            1 -> "Yesterday"
            else -> {
                val fmt = if (target.get(Calendar.YEAR) == today.get(Calendar.YEAR)) sameYearFormat else otherYearFormat
                fmt.format(epochMillis)
            }
        }
    }

    /**
     * Buckets [files] by the calendar day they were created on, newest day first. Within each
     * day, files are sorted largest-first -- the day groups them by "when", the ordering inside
     * answers "what's actually taking up space that day".
     */
    fun groupByCreatedDay(files: List<FileEntry>): List<DayGroup> {
        val grouped = files.groupBy { dayKey(it.createdAtMillis) }
        return grouped.entries
            .sortedByDescending { it.key }
            .map { (key, list) ->
                val bySize = list.sortedByDescending { it.sizeBytes }
                DayGroup(key, dayLabel(bySize.first().createdAtMillis), bySize)
            }
    }

    private fun daysBetween(a: Calendar, b: Calendar): Int {
        val start = a.clone() as Calendar
        start.set(Calendar.HOUR_OF_DAY, 0); start.set(Calendar.MINUTE, 0)
        start.set(Calendar.SECOND, 0); start.set(Calendar.MILLISECOND, 0)
        val end = b.clone() as Calendar
        end.set(Calendar.HOUR_OF_DAY, 0); end.set(Calendar.MINUTE, 0)
        end.set(Calendar.SECOND, 0); end.set(Calendar.MILLISECOND, 0)
        val diffMillis = end.timeInMillis - start.timeInMillis
        return (diffMillis / (24L * 60 * 60 * 1000)).toInt()
    }
}
