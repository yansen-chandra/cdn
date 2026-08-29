package com.spacetrace.app.util

import com.spacetrace.app.model.FileEntry
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class DayGroup(val groupKey: String, val label: String, val files: List<FileEntry>)

object DateGrouping {

    private val sameYearFormat = SimpleDateFormat("EEEE, MMM d", Locale.US)
    private val otherYearFormat = SimpleDateFormat("EEEE, MMM d, yyyy", Locale.US)
    private val fullDateTimeFormat = SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.getDefault())

    /**
     * The full absolute date and time, e.g. "Aug 26, 2026, 10:07 AM" -- unlike [dayLabel], never
     * relative, so it stays unambiguous inside a multi-day section like "Last week" or "Older".
     */
    fun fullDateTime(epochMillis: Long): String = fullDateTimeFormat.format(epochMillis)

    /** Human label for a calendar day, e.g. "Today", "Yesterday", "Wednesday, Aug 26". */
    fun dayLabel(epochMillis: Long, now: Long = System.currentTimeMillis()): String =
        when (daysAgo(epochMillis, now)) {
            0 -> "Today"
            1 -> "Yesterday"
            else -> {
                val target = Calendar.getInstance().apply { timeInMillis = epochMillis }
                val today = Calendar.getInstance().apply { timeInMillis = now }
                val fmt = if (target.get(Calendar.YEAR) == today.get(Calendar.YEAR)) sameYearFormat else otherYearFormat
                fmt.format(epochMillis)
            }
        }

    /**
     * Buckets [files] by recency: an individual section for each of the last four days (Today,
     * Yesterday, and the two days before that), then progressively coarser buckets further back
     * (last week / last month / last 3 months / older) so old activity doesn't fragment into one
     * section per calendar day forever. Within each section, files are sorted largest-first -- the
     * section groups by "when", the order inside answers "what's actually taking up space".
     */
    fun groupByRecency(files: List<FileEntry>, now: Long = System.currentTimeMillis()): List<DayGroup> {
        val grouped = files.groupBy { RecencyBucket.of(daysAgo(it.createdAtMillis, now)) }
        return grouped.entries
            .sortedBy { it.key.order }
            .map { (bucket, list) ->
                val bySize = list.sortedByDescending { it.sizeBytes }
                val label = bucket.fixedLabel ?: dayLabel(bySize.maxOf { it.createdAtMillis }, now)
                DayGroup(bucket.name, label, bySize)
            }
    }

    private enum class RecencyBucket(val order: Int, val fixedLabel: String?) {
        TODAY(0, null),
        YESTERDAY(1, null),
        D2(2, null),
        D3(3, null),
        LAST_WEEK(4, "Last week"),
        LAST_MONTH(5, "Last month"),
        LAST_3_MONTHS(6, "Last 3 months"),
        OLDER(7, "Older");

        companion object {
            fun of(daysAgo: Int): RecencyBucket = when {
                daysAgo <= 0 -> TODAY
                daysAgo == 1 -> YESTERDAY
                daysAgo == 2 -> D2
                daysAgo == 3 -> D3
                daysAgo in 4..7 -> LAST_WEEK
                daysAgo in 8..30 -> LAST_MONTH
                daysAgo in 31..90 -> LAST_3_MONTHS
                else -> OLDER
            }
        }
    }

    private fun daysAgo(epochMillis: Long, now: Long): Int {
        val target = Calendar.getInstance().apply { timeInMillis = epochMillis }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        return daysBetween(target, today)
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
