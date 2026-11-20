package app.dayroute.trip

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.dayroute.repository

/** Handles notification buttons. */
class TripActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val repo = context.repository
        when (intent.action) {
            ACTION_SKIP -> repo.skipCurrent()
            ACTION_ARRIVED -> repo.markArrived()
        }
    }

    companion object {
        const val ACTION_SKIP = "app.dayroute.SKIP"
        const val ACTION_ARRIVED = "app.dayroute.ARRIVED"

        fun pending(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            context, action.hashCode(),
            Intent(context, TripActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
