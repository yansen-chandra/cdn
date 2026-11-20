package app.dayroute.planner

import kotlin.test.Test
import kotlin.test.assertEquals

class GeoTest {
    @Test
    fun `one degree of latitude is about 111 km`() {
        assertEquals(111_195.0, GeoPoint(0.0, 0.0).distanceMetersTo(GeoPoint(1.0, 0.0)), 50.0)
    }

    @Test
    fun `straight line estimate uses speed and detour factor`() {
        // ~111.2 km * 1.5 detour at 60 km/h = ~166.8 min
        val est = StraightLineEstimator(averageSpeedKmh = 60.0, detourFactor = 1.5)
        assertEquals(167, est.minutesBetween(GeoPoint(0.0, 0.0), GeoPoint(1.0, 0.0)))
    }

    @Test
    fun `straight line estimate is never zero between different places`() {
        val est = StraightLineEstimator()
        assertEquals(1, est.minutesBetween(GeoPoint(0.0, 0.0), GeoPoint(0.0001, 0.0)))
    }
}
