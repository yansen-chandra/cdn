package app.dayroute.planner

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Stops placed on a straight line: longitude is "units along the road",
 * and each unit takes [minutesPerUnit] minutes to drive. Keeps expected
 * times easy to work out by hand.
 */
fun lineEstimator(minutesPerUnit: Int = 10) = TravelTimeEstimator { from, to ->
    (abs(from.lng - to.lng) * minutesPerUnit).roundToInt()
}

fun at(x: Double) = GeoPoint(lat = 0.0, lng = x)

fun stop(
    id: String,
    x: Double,
    window: TimeWindow? = null,
    dwellMinutes: Int = 0,
) = Stop(id = id, name = id, location = at(x), window = window, dwellMinutes = dwellMinutes)

fun t(text: String) = ClockTime.parse(text)
