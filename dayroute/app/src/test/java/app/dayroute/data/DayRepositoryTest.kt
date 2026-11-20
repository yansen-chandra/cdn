package app.dayroute.data

import app.dayroute.planner.ClockTime
import app.dayroute.planner.GeoPoint
import app.dayroute.planner.Stop
import app.dayroute.planner.StraightLineEstimator
import app.dayroute.planner.TimeWindowPlanner
import app.dayroute.planner.TripEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DayRepositoryTest {
    private val dir: File = Files.createTempDirectory("dayroute").toFile()
    private val file = File(dir, "day.json")
    private fun repo() = DayRepository(file, TimeWindowPlanner(StraightLineEstimator()))

    private val home = GeoPoint(1.000, 103.0)
    private val a = Stop("A", "A", GeoPoint(1.010, 103.0))
    private val b = Stop("B", "B", GeoPoint(1.020, 103.0))

    @Test
    fun `stops and an active trip survive an app restart`() {
        repo().apply {
            addStop(a); addStop(b)
            setLeaveAt(ClockTime.parse("08:00"))
            startTrip(home)
            markArrived()
        }
        val reopened = repo().state.value
        assertEquals(listOf("A", "B"), reopened.stops.map { it.id })
        assertEquals("B", reopened.trip?.current?.stop?.id)
    }

    @Test
    fun `reaching a stop by GPS announces the next one`() = runTest(UnconfinedTestDispatcher()) {
        val repo = repo().apply { addStop(a); addStop(b); setLeaveAt(ClockTime.parse("08:00")); startTrip(home) }
        val event = async { repo.events.first() }
        repo.onLocation(a.location)
        assertEquals(TripEvent.Arrived(arrivedAt = a, next = b), event.await())
    }

    @Test
    fun `ending the day keeps only the stops that were not visited`() {
        val repo = repo().apply { addStop(a); addStop(b); setLeaveAt(ClockTime.parse("08:00")); startTrip(home); markArrived() }
        repo.endTrip()
        assertNull(repo.state.value.trip)
        assertEquals(listOf("B"), repo.state.value.stops.map { it.id })
    }
}
