package app.dayroute.planner

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_M = 6_371_008.8

/** Great-circle (haversine) distance in metres. */
fun GeoPoint.distanceMetersTo(other: GeoPoint): Double {
    val dLat = Math.toRadians(other.lat - lat)
    val dLng = Math.toRadians(other.lng - lng)
    val a = sin(dLat / 2).let { it * it } +
        cos(Math.toRadians(lat)) * cos(Math.toRadians(other.lat)) * sin(dLng / 2).let { it * it }
    return 2 * EARTH_RADIUS_M * asin(sqrt(a))
}

/**
 * Offline drive-time guess: straight-line distance, stretched by [detourFactor]
 * for real roads, at [averageSpeedKmh]. Good enough to order a day's stops;
 * swap for a routing API when real traffic matters.
 */
class StraightLineEstimator(
    private val averageSpeedKmh: Double = 35.0,
    private val detourFactor: Double = 1.35,
) : TravelTimeEstimator {
    override fun minutesBetween(from: GeoPoint, to: GeoPoint): Int {
        if (from == to) return 0
        val km = from.distanceMetersTo(to) / 1000.0 * detourFactor
        return max(1, (km / averageSpeedKmh * 60.0).roundToInt())
    }
}
