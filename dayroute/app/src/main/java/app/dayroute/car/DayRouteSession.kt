package app.dayroute.car

import android.content.Intent
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.lifecycleScope
import app.dayroute.planner.Stop
import app.dayroute.planner.TripEvent
import app.dayroute.repository
import app.dayroute.trip.Navigation
import kotlinx.coroutines.launch

class DayRouteSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        val repo = carContext.repository
        // While the car session is alive: on arrival, hand the next stop to the nav app.
        // If the host refuses because we're in the background, the heads-up
        // notification (TripNotifications) still offers a one-tap "Navigate".
        lifecycleScope.launch {
            repo.events.collect { event ->
                if (event is TripEvent.Arrived && event.next != null && repo.state.value.autoNavigateNext) {
                    carContext.navigateTo(event.next!!)
                }
            }
        }
        handle(intent)
        return TripScreen(carContext)
    }

    override fun onNewIntent(intent: Intent) = handle(intent)

    private fun handle(intent: Intent) {
        if (intent.action == ACTION_NAVIGATE_CURRENT) {
            carContext.repository.state.value.trip?.current?.let { carContext.navigateTo(it.stop) }
        }
    }

    companion object {
        const val ACTION_NAVIGATE_CURRENT = "app.dayroute.car.NAVIGATE_CURRENT"
    }
}

fun CarContext.navigateTo(stop: Stop) {
    try {
        startCarApp(Intent(CarContext.ACTION_NAVIGATE, Navigation.carUri(stop)))
    } catch (e: Exception) {
        CarToast.makeText(this, "Couldn't open navigation for ${stop.name}", CarToast.LENGTH_LONG).show()
    }
}
