package app.dayroute.planner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TripTest {
    // Real coordinates: 0.001 deg of latitude is ~111 m.
    private val a = Stop("A", "A", GeoPoint(1.000, 103.0))
    private val b = Stop("B", "B", GeoPoint(1.010, 103.0))
    private val c = Stop("C", "C", GeoPoint(1.020, 103.0))

    private fun planned(vararg stops: Stop) = DayPlan(
        stops.mapIndexed { i, s -> PlannedStop(s, ClockTime.parse("09:00") + i * 10, 10, 0, 0) }
    )

    private val trip = Trip.start(planned(a, b, c))

    @Test
    fun `a new trip heads to the first stop`() {
        assertEquals("A", trip.current?.stop?.id)
        assertEquals(false, trip.finished)
    }

    @Test
    fun `being far from the stop changes nothing`() {
        val update = trip.onLocation(GeoPoint(1.003, 103.0)) // ~330 m away
        assertNull(update.event)
        assertEquals("A", update.trip.current?.stop?.id)
    }

    @Test
    fun `reaching the stop marks it visited and switches to the next one`() {
        val update = trip.onLocation(GeoPoint(1.0005, 103.0)) // ~55 m away
        assertEquals(TripEvent.Arrived(arrivedAt = a, next = b), update.event)
        assertEquals(StopStatus.VISITED, update.trip.statusOf("A"))
        assertEquals("B", update.trip.current?.stop?.id)
    }

    @Test
    fun `passing near a later stop does not jump ahead`() {
        val update = trip.onLocation(b.location)
        assertNull(update.event)
        assertEquals("A", update.trip.current?.stop?.id)
    }

    @Test
    fun `reaching the last stop finishes the trip`() {
        var t = trip
        t = t.onLocation(a.location).trip
        t = t.onLocation(b.location).trip
        val last = t.onLocation(c.location)
        assertEquals(TripEvent.Arrived(arrivedAt = c, next = null), last.event)
        assertTrue(last.trip.finished)
        assertNull(last.trip.current)
    }

    @Test
    fun `skipping moves on without visiting`() {
        val t = trip.skipCurrent()
        assertEquals(StopStatus.SKIPPED, t.statusOf("A"))
        assertEquals("B", t.current?.stop?.id)
    }

    @Test
    fun `manually marking arrival works without a location fix`() {
        val update = trip.markArrived()
        assertEquals(TripEvent.Arrived(arrivedAt = a, next = b), update.event)
        assertEquals("B", update.trip.current?.stop?.id)
    }

    @Test
    fun `replanning reorders only the stops still to go`() {
        val afterA = trip.onLocation(a.location).trip
        // Standing just past C at 10:00: C is now closer than B.
        val replanned = afterA.replan(
            TimeWindowPlanner(StraightLineEstimator()),
            from = GeoPoint(1.025, 103.0),
            now = ClockTime.parse("10:00"),
        )
        assertEquals(listOf("A", "C", "B"), replanned.plan.stops.map { it.stop.id })
        assertEquals(StopStatus.VISITED, replanned.statusOf("A"))
        assertEquals("C", replanned.current?.stop?.id)
        assertTrue(replanned.current!!.arrival >= ClockTime.parse("10:00"))
    }
}
