package app.dayroute.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarLocation
import androidx.car.app.model.ItemList
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Metadata
import androidx.car.app.model.Place
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.PlaceMarker
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.dayroute.data.DeviceLocation
import app.dayroute.planner.PlannedStop
import app.dayroute.repository
import app.dayroute.trip.TripNotifications
import app.dayroute.trip.TripTrackingService
import kotlinx.coroutines.launch

/**
 * The one screen a driver needs: today's stops on the map, the next one on top.
 * Planning (typing addresses, windows) stays on the phone - the car only drives the day.
 */
class TripScreen(carContext: CarContext) : Screen(carContext) {
    private val repo = carContext.repository

    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.state.collect { invalidate() }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val state = repo.state.value
        val trip = state.trip
        return when {
            trip == null && state.stops.isEmpty() -> MessageTemplate.Builder(
                "Add today's stops in DayRoute on your phone, then come back here."
            ).setTitle("No stops yet").setHeaderAction(Action.APP_ICON).build()

            trip == null -> {
                val from = DeviceLocation.lastKnown(carContext) ?: state.stops.first().location
                val preview = repo.preview(from)
                placeList(
                    title = "Today · ${preview.stops.size} stops",
                    stops = preview.stops,
                    action = Action.Builder().setTitle("Start").setBackgroundColor(CarColor.BLUE)
                        .setOnClickListener {
                            repo.startTrip(from)
                            TripTrackingService.start(carContext)
                            repo.state.value.trip?.current?.let { carContext.navigateTo(it.stop) }
                        }.build(),
                    activeId = null,
                )
            }

            trip.finished -> MessageTemplate.Builder("All stops done. Nice driving!")
                .setTitle("Day complete")
                .setHeaderAction(Action.APP_ICON)
                .addAction(Action.Builder().setTitle("End day").setOnClickListener { repo.endTrip() }.build())
                .build()

            else -> {
                val current = trip.current!!
                placeList(
                    title = "Next: ${current.stop.name}",
                    stops = trip.remaining,
                    action = Action.Builder().setTitle("Re-plan")
                        .setOnClickListener { repo.replan(DeviceLocation.lastKnown(carContext)) }.build(),
                    activeId = current.stop.id,
                )
            }
        }
    }

    private fun placeList(title: String, stops: List<PlannedStop>, action: Action, activeId: String?): Template {
        val list = ItemList.Builder()
        stops.take(maxRows()).forEachIndexed { i, p ->
            val place = Place.Builder(CarLocation.create(p.stop.location.lat, p.stop.location.lng))
                .setMarker(
                    PlaceMarker.Builder().setLabel("${i + 1}")
                        .apply { if (p.stop.id == activeId) setColor(CarColor.BLUE) }
                        .build()
                ).build()
            list.addItem(
                Row.Builder()
                    .setTitle("${i + 1}. ${p.stop.name}")
                    .addText(TripNotifications.describe(p))
                    .setMetadata(Metadata.Builder().setPlace(place).build())
                    .setOnClickListener { screenManager.push(StopScreen(carContext, p.stop.id)) }
                    .build()
            )
        }
        return PlaceListMapTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.APP_ICON)
            .setCurrentLocationEnabled(DeviceLocation.hasPermission(carContext))
            .setItemList(list.build())
            .setActionStrip(ActionStrip.Builder().addAction(action).build())
            .build()
    }

    private fun maxRows(): Int =
        if (carContext.carAppApiLevel >= 2) {
            carContext.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_PLACE_LIST)
        } else 6
}
