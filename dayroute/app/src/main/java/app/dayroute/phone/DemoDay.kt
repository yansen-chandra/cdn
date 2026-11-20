package app.dayroute.phone

import app.dayroute.planner.ClockTime
import app.dayroute.planner.GeoPoint
import app.dayroute.planner.Stop
import app.dayroute.planner.TimeWindow

/** A sample day placed a few km around the driver, to try the app without typing addresses. */
object DemoDay {
    fun around(center: GeoPoint?): List<Stop> {
        val c = center ?: GeoPoint(1.2966, 103.8520) // fallback: any city centre
        fun near(dLat: Double, dLng: Double) = GeoPoint(c.lat + dLat, c.lng + dLng)
        return listOf(
            Stop("demo-dentist", "Dentist", near(0.030, -0.020), TimeWindow(ClockTime.parse("10:00"), ClockTime.parse("10:15"), strict = true), dwellMinutes = 45),
            Stop("demo-post", "Post office", near(-0.010, 0.015), TimeWindow(latest = ClockTime.parse("12:00")), dwellMinutes = 10),
            Stop("demo-grocery", "Groceries", near(0.012, 0.025), dwellMinutes = 30),
            Stop("demo-school", "School pickup", near(-0.022, -0.012), TimeWindow(ClockTime.parse("15:00"), ClockTime.parse("15:15"), strict = true), dwellMinutes = 10),
            Stop("demo-pharmacy", "Pharmacy", near(0.005, -0.030), TimeWindow(latest = ClockTime.parse("18:00")), dwellMinutes = 10),
        )
    }
}
