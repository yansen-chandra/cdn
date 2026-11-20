package app.dayroute.planner

import kotlinx.serialization.Serializable

/** Time of day as minutes since midnight. Values past 24h mean "the next day". */
@Serializable
@JvmInline
value class ClockTime(val minutes: Int) : Comparable<ClockTime> {
    operator fun plus(deltaMinutes: Int) = ClockTime(minutes + deltaMinutes)
    operator fun minus(other: ClockTime): Int = minutes - other.minutes
    override fun compareTo(other: ClockTime) = minutes.compareTo(other.minutes)

    override fun toString(): String {
        val dayOffset = minutes / MINUTES_PER_DAY
        val inDay = minutes % MINUTES_PER_DAY
        val base = "%02d:%02d".format(inDay / 60, inDay % 60)
        return if (dayOffset > 0) "$base+$dayOffset" else base
    }

    companion object {
        private const val MINUTES_PER_DAY = 24 * 60

        fun of(hour: Int, minute: Int) = ClockTime(hour * 60 + minute)

        /** Parses "H:mm" or "HH:mm". */
        fun parse(text: String): ClockTime {
            val (h, m) = text.trim().split(":").map { it.toInt() }
            require(h in 0..23 && m in 0..59) { "Not a valid time: $text" }
            return of(h, m)
        }
    }
}

@Serializable
data class GeoPoint(val lat: Double, val lng: Double)

/**
 * When a stop should be reached.
 *
 * [strict] windows are appointments: the planner treats arriving after [latest]
 * as a failure to avoid at almost any cost. Relaxed windows are preferences.
 * Arriving before [earliest] always means waiting until it opens.
 */
@Serializable
data class TimeWindow(
    val earliest: ClockTime? = null,
    val latest: ClockTime? = null,
    val strict: Boolean = false,
)

@Serializable
data class Stop(
    val id: String,
    val name: String,
    val location: GeoPoint,
    val window: TimeWindow? = null,
    /** How long you expect to stay at the stop. */
    val dwellMinutes: Int = 0,
    val address: String? = null,
)

data class PlanRequest(
    val start: GeoPoint,
    val startTime: ClockTime,
    val stops: List<Stop>,
)

@Serializable
data class PlannedStop(
    val stop: Stop,
    val arrival: ClockTime,
    val driveMinutes: Int,
    val waitMinutes: Int,
    val lateMinutes: Int,
) {
    val serviceStart: ClockTime get() = arrival + waitMinutes
    val departure: ClockTime get() = serviceStart + stop.dwellMinutes
}

@Serializable
data class DayPlan(val stops: List<PlannedStop>) {
    val totalDriveMinutes: Int get() = stops.sumOf { it.driveMinutes }
    val missedStrictStops: List<Stop>
        get() = stops.filter { it.lateMinutes > 0 && it.stop.window?.strict == true }.map { it.stop }
    val finishTime: ClockTime? get() = stops.lastOrNull()?.departure
}

/** Swappable so a real routing API (or traffic data) can replace the estimate. */
fun interface TravelTimeEstimator {
    fun minutesBetween(from: GeoPoint, to: GeoPoint): Int
}

/** Swappable so an AI planner can be dropped in later behind the same contract. */
fun interface RoutePlanner {
    fun plan(request: PlanRequest): DayPlan
}
