package app.dayroute.trip

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.dayroute.planner.Stop

/**
 * Turn-by-turn is handed to the driver's navigation app (Google Maps, Waze, ...).
 * DayRoute owns the order of the day; the nav app owns the road.
 */
object Navigation {
    fun phoneIntent(stop: Stop): Intent {
        val (lat, lng) = stop.location
        return Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lng"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun genericIntent(stop: Stop): Intent {
        val (lat, lng) = stop.location
        val label = Uri.encode(stop.name)
        return Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng($label)"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun openOnPhone(context: Context, stop: Stop) {
        try {
            context.startActivity(phoneIntent(stop))
        } catch (e: ActivityNotFoundException) {
            context.startActivity(Intent.createChooser(genericIntent(stop), "Navigate with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** URI understood by Android Auto's CarContext.ACTION_NAVIGATE. */
    fun carUri(stop: Stop): Uri {
        val (lat, lng) = stop.location
        return Uri.parse("geo:$lat,$lng")
    }
}
