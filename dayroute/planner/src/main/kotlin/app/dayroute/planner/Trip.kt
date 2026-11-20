package app.dayroute.planner

import kotlinx.serialization.Serializable

@Serializable
enum class StopStatus { PENDING, VISITED, SKIPPED }

sealed interface TripEvent {
    data class Arrived(val arrivedAt: Stop, val next: Stop?) : TripEvent
}

data class TripUpdate(val trip: Trip, val event: TripEvent? = null)

/**
 * A day being driven. Immutable: every change returns a new [Trip], which makes
 * it easy to share between the phone UI and the car screen as one state value.
 *
 * Stops are visited in plan order; the [current] stop is the first one still pending.
 */
@Serializable
data class Trip(
    val plan: DayPlan,
    val statuses: Map<String, StopStatus>,
    val arrivalRadiusMeters: Double = DEFAULT_ARRIVAL_RADIUS_M,
) {
    val current: PlannedStop? get() = plan.stops.firstOrNull { statusOf(it.stop.id) == StopStatus.PENDING }
    val finished: Boolean get() = current == null
    val remaining: List<PlannedStop> get() = plan.stops.filter { statusOf(it.stop.id) == StopStatus.PENDING }

    fun statusOf(id: String): StopStatus = statuses[id] ?: StopStatus.PENDING

    /** Feed each GPS fix here; arriving near the current stop advances to the next. */
    fun onLocation(point: GeoPoint): TripUpdate {
        val target = current ?: return TripUpdate(this)
        return if (point.distanceMetersTo(target.stop.location) <= arrivalRadiusMeters) markArrived() else TripUpdate(this)
    }

    fun markArrived(): TripUpdate {
        val target = current ?: return TripUpdate(this)
        val next = with(StopStatus.VISITED)
        return TripUpdate(next, TripEvent.Arrived(arrivedAt = target.stop, next = next.current?.stop))
    }

    fun skipCurrent(): Trip = if (current == null) this else with(StopStatus.SKIPPED)

    /** Re-orders the stops still to go, from where you are now; finished stops keep their place. */
    fun replan(planner: RoutePlanner, from: GeoPoint, now: ClockTime): Trip {
        val done = plan.stops.filter { statusOf(it.stop.id) != StopStatus.PENDING }
        val fresh = planner.plan(PlanRequest(from, now, remaining.map { it.stop }))
        return copy(plan = DayPlan(done + fresh.stops))
    }

    private fun with(status: StopStatus): Trip =
        copy(statuses = statuses + (current!!.stop.id to status))

    companion object {
        const val DEFAULT_ARRIVAL_RADIUS_M = 80.0
        fun start(plan: DayPlan) = Trip(plan, emptyMap())
    }
}
