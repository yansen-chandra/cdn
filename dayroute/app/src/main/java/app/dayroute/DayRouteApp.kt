package app.dayroute

import android.app.Application
import app.dayroute.data.DayRepository
import app.dayroute.planner.StraightLineEstimator
import app.dayroute.planner.TimeWindowPlanner
import app.dayroute.trip.TripNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class DayRouteApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var repository: DayRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = DayRepository(
            file = File(filesDir, "day.json"),
            // Swap this for an AI- or traffic-aware planner later: anything implementing RoutePlanner.
            planner = TimeWindowPlanner(StraightLineEstimator()),
        )
        TripNotifications.createChannels(this)
        // Every arrival (from GPS or a button) raises a "next stop" alert on phone + car.
        appScope.launch {
            repository.events.collect { TripNotifications.showArrival(this@DayRouteApp, it, repository.state.value) }
        }
    }
}

val android.content.Context.repository: DayRepository
    get() = (applicationContext as DayRouteApp).repository
