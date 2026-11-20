package app.dayroute.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import app.dayroute.repository
import app.dayroute.trip.TripNotifications

/** One stop: navigate there, or (for the stop you're heading to) mark arrived / skip. */
class StopScreen(carContext: CarContext, private val stopId: String) : Screen(carContext) {
    private val repo = carContext.repository

    override fun onGetTemplate(): Template {
        val state = repo.state.value
        val trip = state.trip
        val planned = trip?.plan?.stops?.firstOrNull { it.stop.id == stopId }
            ?: return MessageTemplate.Builder("This stop is no longer in today's plan.")
                .setHeaderAction(Action.BACK).build()
        val isCurrent = trip.current?.stop?.id == stopId
        val stop = planned.stop

        val pane = Pane.Builder()
            .addRow(Row.Builder().setTitle(TripNotifications.describe(planned)).build())
        stop.address?.let { pane.addRow(Row.Builder().setTitle(it).build()) }
        if (stop.dwellMinutes > 0) pane.addRow(Row.Builder().setTitle("Staying ~${stop.dwellMinutes} min").build())

        pane.addAction(
            Action.Builder().setTitle("Navigate").setBackgroundColor(CarColor.BLUE)
                .setOnClickListener { carContext.navigateTo(stop) }.build()
        )
        if (isCurrent) {
            pane.addAction(
                Action.Builder().setTitle("I'm here").setOnClickListener {
                    repo.markArrived()
                    screenManager.pop()
                }.build()
            )
        }
        val template = PaneTemplate.Builder(pane.build())
            .setTitle(stop.name)
            .setHeaderAction(Action.BACK)
        if (isCurrent) {
            template.setActionStrip(
                ActionStrip.Builder().addAction(
                    Action.Builder().setTitle("Skip").setOnClickListener {
                        repo.skipCurrent()
                        screenManager.pop()
                    }.build()
                ).build()
            )
        }
        return template.build()
    }
}
