package app.dayroute.data

import app.dayroute.planner.ClockTime
import app.dayroute.planner.DayPlan
import app.dayroute.planner.GeoPoint
import app.dayroute.planner.PlanRequest
import app.dayroute.planner.RoutePlanner
import app.dayroute.planner.Stop
import app.dayroute.planner.Trip
import app.dayroute.planner.TripEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalTime

@Serializable
data class DayState(
    /** The stops the driver entered, in the order they entered them. */
    val stops: List<Stop> = emptyList(),
    /** When the day starts; null means "now". */
    val leaveAt: ClockTime? = null,
    /** The day being driven, once started. */
    val trip: Trip? = null,
    /** On arrival, open navigation to the next stop straight away when possible. */
    val autoNavigateNext: Boolean = true,
)

/**
 * Single source of truth shared by the phone UI, the car screens and the
 * location service. Saved to a small JSON file so a trip survives restarts.
 */
class DayRepository(
    private val file: File,
    private val planner: RoutePlanner,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(load())
    val state: StateFlow<DayState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<TripEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<TripEvent> = _events

    @Volatile var lastLocation: GeoPoint? = null
        private set

    fun addStop(stop: Stop) = update { it.copy(stops = it.stops + stop) }
    fun removeStop(id: String) = update { it.copy(stops = it.stops.filterNot { s -> s.id == id }) }
    fun replaceStops(stops: List<Stop>) = update { it.copy(stops = stops) }
    fun setLeaveAt(time: ClockTime?) = update { it.copy(leaveAt = time) }
    fun setAutoNavigateNext(on: Boolean) = update { it.copy(autoNavigateNext = on) }

    fun preview(from: GeoPoint): DayPlan {
        val s = _state.value
        return planner.plan(PlanRequest(from, s.leaveAt ?: nowClock(), s.stops))
    }

    fun startTrip(from: GeoPoint) {
        val plan = preview(from)
        if (plan.stops.isEmpty()) return
        update { it.copy(trip = Trip.start(plan)) }
    }

    fun onLocation(point: GeoPoint) {
        lastLocation = point
        val trip = _state.value.trip ?: return
        val result = trip.onLocation(point)
        if (result.event != null) {
            update { it.copy(trip = result.trip) }
            _events.tryEmit(result.event!!)
        }
    }

    fun markArrived() {
        val result = _state.value.trip?.markArrived() ?: return
        update { it.copy(trip = result.trip) }
        result.event?.let { _events.tryEmit(it) }
    }

    fun skipCurrent() = update { s -> s.copy(trip = s.trip?.skipCurrent()) }

    /** Re-order what's left from where you are now (e.g. after running late). */
    fun replan(from: GeoPoint?) = update { s ->
        val trip = s.trip ?: return@update s
        val origin = from ?: lastLocation ?: trip.current?.stop?.location ?: return@update s
        s.copy(trip = trip.replan(planner, origin, nowClock()))
    }

    /** Ends the day; the visited stops are cleared so tomorrow starts fresh. */
    fun endTrip() = update { s ->
        val visited = s.trip?.statuses?.filterValues { it.name == "VISITED" }?.keys.orEmpty()
        s.copy(trip = null, stops = s.stops.filterNot { it.id in visited })
    }

    @Synchronized
    private fun update(transform: (DayState) -> DayState) {
        val next = transform(_state.value)
        if (next == _state.value) return
        _state.value = next
        runCatching { file.writeText(json.encodeToString(DayState.serializer(), next)) }
    }

    private fun load(): DayState = runCatching {
        if (file.exists()) json.decodeFromString(DayState.serializer(), file.readText()) else DayState()
    }.getOrDefault(DayState())
}

fun nowClock(): ClockTime = LocalTime.now().let { ClockTime.of(it.hour, it.minute) }
