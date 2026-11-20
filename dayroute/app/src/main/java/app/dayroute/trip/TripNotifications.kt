package app.dayroute.trip

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarNotificationManager
import androidx.car.app.notification.CarPendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.dayroute.R
import app.dayroute.car.DayRouteCarAppService
import app.dayroute.car.DayRouteSession
import app.dayroute.data.DayState
import app.dayroute.phone.MainActivity
import app.dayroute.planner.PlannedStop
import app.dayroute.planner.TripEvent

object TripNotifications {
    const val CHANNEL_TRACKING = "tracking"
    const val CHANNEL_ARRIVAL = "arrival"
    const val ID_TRACKING = 1
    private const val ID_ARRIVAL = 2

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRACKING, "Trip in progress", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ARRIVAL, "Arrived / next stop", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    fun describe(p: PlannedStop): String = buildString {
        append("ETA ").append(p.arrival)
        val w = p.stop.window
        if (w != null && (w.earliest != null || w.latest != null)) {
            append(" · ").append(if (w.strict) "strict " else "")
            when {
                w.earliest != null && w.latest != null -> append("${w.earliest}–${w.latest}")
                w.earliest != null -> append("after ${w.earliest}")
                else -> append("by ${w.latest}")
            }
        }
        if (p.lateMinutes > 0) append(" · ${p.lateMinutes} min late")
    }

    fun tracking(context: Context, state: DayState) = NotificationCompat.Builder(context, CHANNEL_TRACKING)
        .setSmallIcon(R.drawable.ic_stat_route)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(openApp(context))
        .apply {
            val current = state.trip?.current
            if (current == null) setContentTitle("Day finished")
            else setContentTitle("Next: ${current.stop.name}").setContentText(describe(current))
        }
        .build()

    /** "Arrived at A — next is B": one tap to navigate on the phone or in the car. */
    fun showArrival(context: Context, event: TripEvent, state: DayState) {
        if (event !is TripEvent.Arrived) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val next = state.trip?.current
        val title = "Arrived at ${event.arrivedAt.name}"
        val text = if (next == null) "That was the last stop. Day done!" else "Next: ${next.stop.name} · ${describe(next)}"

        val builder = NotificationCompat.Builder(context, CHANNEL_ARRIVAL)
            .setSmallIcon(R.drawable.ic_stat_route)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))

        val car = CarAppExtender.Builder()
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_route)
            .setImportance(NotificationManagerCompat.IMPORTANCE_HIGH)

        if (next != null) {
            val navPhone = PendingIntent.getActivity(
                context, 10, Navigation.phoneIntent(next.stop), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(0, "Navigate", navPhone)
            builder.addAction(0, "Skip it", TripActionReceiver.pending(context, TripActionReceiver.ACTION_SKIP))

            val navCar = CarPendingIntent.getCarApp(
                context, 11,
                Intent(DayRouteSession.ACTION_NAVIGATE_CURRENT)
                    .setComponent(ComponentName(context, DayRouteCarAppService::class.java)),
                0,
            )
            car.addAction(R.drawable.ic_stat_route, "Navigate", navCar)
            car.setContentIntent(navCar)
        }
        builder.extend(car.build())
        CarNotificationManager.from(context).notify(ID_ARRIVAL, builder)
    }

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )
}
