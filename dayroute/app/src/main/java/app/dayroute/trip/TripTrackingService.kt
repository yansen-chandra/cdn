package app.dayroute.trip

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.dayroute.data.DeviceLocation
import app.dayroute.planner.GeoPoint
import app.dayroute.repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Follows the phone's GPS while a trip is active so arrival is detected even when
 * the nav app is in front (on the phone or on the car screen). Stops itself when
 * the trip ends.
 */
class TripTrackingService : Service(), LocationListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tracking = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val repo = repository
        ServiceCompat.startForeground(
            this, TripNotifications.ID_TRACKING, TripNotifications.tracking(this, repo.state.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
        )
        if (!tracking) {
            tracking = startUpdates()
            scope.launch {
                repo.state.collectLatest { state ->
                    if (state.trip == null || state.trip.finished) {
                        stopSelf()
                    } else if (NotificationManagerCompat.from(this@TripTrackingService).areNotificationsEnabled()) {
                        @SuppressLint("MissingPermission")
                        NotificationManagerCompat.from(this@TripTrackingService)
                            .notify(TripNotifications.ID_TRACKING, TripNotifications.tracking(this@TripTrackingService, state))
                    }
                }
            }
        }
        if (!tracking) stopSelf()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates(): Boolean {
        if (!DeviceLocation.hasPermission(this)) return false
        val lm = getSystemService(LocationManager::class.java) ?: return false
        var any = false
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (runCatching { lm.isProviderEnabled(provider) }.getOrDefault(false)) {
                lm.requestLocationUpdates(provider, 4_000L, 10f, this, Looper.getMainLooper())
                any = true
            }
        }
        return any
    }

    override fun onLocationChanged(location: Location) {
        repository.onLocation(GeoPoint(location.latitude, location.longitude))
    }

    override fun onDestroy() {
        getSystemService(LocationManager::class.java)?.removeUpdates(this)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            if (!DeviceLocation.hasPermission(context)) return
            ContextCompat.startForegroundService(context, Intent(context, TripTrackingService::class.java))
        }
    }
}
