package app.dayroute.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import androidx.core.content.ContextCompat
import app.dayroute.repository
import app.dayroute.planner.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PlaceResult(val label: String, val point: GeoPoint)

/** Address search using the platform geocoder (no API key needed). */
object Places {
    @Suppress("DEPRECATION")
    suspend fun search(context: Context, query: String, near: GeoPoint?): List<PlaceResult> = withContext(Dispatchers.IO) {
        if (query.isBlank() || !Geocoder.isPresent()) return@withContext emptyList()
        runCatching {
            val geocoder = Geocoder(context)
            val results = if (near != null) {
                // Bias results to roughly +/- 1 degree around the driver.
                geocoder.getFromLocationName(query, 6, near.lat - 1, near.lng - 1, near.lat + 1, near.lng + 1)
                    ?.takeIf { it.isNotEmpty() } ?: geocoder.getFromLocationName(query, 6)
            } else geocoder.getFromLocationName(query, 6)
            results.orEmpty().map { a ->
                val label = (0..a.maxAddressLineIndex).joinToString(", ") { a.getAddressLine(it) }
                PlaceResult(label.ifBlank { query }, GeoPoint(a.latitude, a.longitude))
            }
        }.getOrDefault(emptyList())
    }
}

object DeviceLocation {
    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Best recent fix the phone already has, or null. */
    @SuppressLint("MissingPermission")
    fun lastKnown(context: Context): GeoPoint? {
        context.repository.lastLocation?.let { return it }
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        return lm.getProviders(true)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { GeoPoint(it.latitude, it.longitude) }
    }
}

