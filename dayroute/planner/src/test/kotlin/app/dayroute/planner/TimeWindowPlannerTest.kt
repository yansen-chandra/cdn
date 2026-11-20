package app.dayroute.planner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimeWindowPlannerTest {
    private val planner = TimeWindowPlanner(lineEstimator(minutesPerUnit = 10))
    private val origin = at(0.0)

    private fun ids(plan: DayPlan) = plan.stops.map { it.stop.id }
    private fun arrivals(plan: DayPlan) = plan.stops.map { it.arrival.toString() }

    @Test
    fun `no stops gives an empty plan`() {
        val plan = planner.plan(PlanRequest(origin, t("09:00"), emptyList()))
        assertEquals(emptyList(), plan.stops)
    }

    @Test
    fun `without time windows stops are visited in shortest driving order`() {
        val plan = planner.plan(
            PlanRequest(origin, t("09:00"), listOf(stop("C", 3.0), stop("A", 1.0), stop("B", 2.0)))
        )
        assertEquals(listOf("A", "B", "C"), ids(plan))
        assertEquals(listOf("09:10", "09:20", "09:30"), arrivals(plan))
        assertEquals(30, plan.totalDriveMinutes)
    }

    @Test
    fun `strict deadline is honoured even when it makes the drive longer`() {
        // Shortest drive is A(+1) then B(-3), reaching B at 09:50 - too late.
        val b = stop("B", -3.0, TimeWindow(latest = t("09:30"), strict = true))
        val plan = planner.plan(PlanRequest(origin, t("09:00"), listOf(stop("A", 1.0), b)))
        assertEquals(listOf("B", "A"), ids(plan))
        assertEquals(listOf("09:30", "10:10"), arrivals(plan))
        assertEquals(emptyList(), plan.missedStrictStops)
    }

    @Test
    fun `arriving before a window opens means waiting until it opens`() {
        val plan = planner.plan(
            PlanRequest(origin, t("09:00"), listOf(stop("A", 1.0, TimeWindow(earliest = t("10:00")), dwellMinutes = 15)))
        )
        val a = plan.stops.single()
        assertEquals("09:10", a.arrival.toString())
        assertEquals(50, a.waitMinutes)
        assertEquals("10:15", a.departure.toString())
    }

    @Test
    fun `time spent at a stop delays the next arrival`() {
        val plan = planner.plan(
            PlanRequest(origin, t("09:00"), listOf(stop("A", 1.0, dwellMinutes = 20), stop("B", 2.0)))
        )
        assertEquals(listOf("09:10", "09:40"), arrivals(plan))
    }

    @Test
    fun `when both cannot be met the strict stop wins over the flexible one`() {
        val a = stop("A", 1.0, TimeWindow(latest = t("09:15"), strict = false))
        val b = stop("B", -3.0, TimeWindow(latest = t("09:30"), strict = true))
        val plan = planner.plan(PlanRequest(origin, t("09:00"), listOf(a, b)))
        assertEquals(listOf("B", "A"), ids(plan))
        assertEquals(55, plan.stops.last().lateMinutes)
        assertEquals(emptyList(), plan.missedStrictStops)
    }

    @Test
    fun `an impossible strict deadline is still visited and reported as missed`() {
        val a = stop("A", 3.0, TimeWindow(latest = t("09:05"), strict = true))
        val plan = planner.plan(PlanRequest(origin, t("09:00"), listOf(a)))
        assertEquals(listOf("A"), ids(plan))
        assertEquals(listOf("A"), plan.missedStrictStops.map { it.id })
        assertEquals(25, plan.stops.single().lateMinutes)
    }

    @Test
    fun `many stops are still put in a sensible order`() {
        val xs = listOf(7, 2, 11, 5, 1, 9, 12, 3, 10, 4, 8, 6)
        val stops = xs.map { stop("S$it", it.toDouble()) }
        val plan = planner.plan(PlanRequest(origin, t("08:00"), stops))
        assertEquals((1..12).map { "S$it" }, ids(plan))
        assertEquals(120, plan.totalDriveMinutes)
    }

    @Test
    fun `many stops with a strict early deadline far away go there first`() {
        val far = stop("FAR", -5.0, TimeWindow(latest = t("08:50"), strict = true))
        val stops = (1..10).map { stop("S$it", it.toDouble()) } + far
        val plan = planner.plan(PlanRequest(origin, t("08:00"), stops))
        assertEquals("FAR", ids(plan).first())
        assertTrue(plan.missedStrictStops.isEmpty())
    }
}
