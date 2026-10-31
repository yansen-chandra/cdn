package io.github.yansenchandra.converter.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.github.yansenchandra.converter.CURRENCY
import io.github.yansenchandra.converter.MainActivity
import io.github.yansenchandra.converter.R
import io.github.yansenchandra.converter.Store
import io.github.yansenchandra.converter.engine.MasterList
import io.github.yansenchandra.converter.engine.Rates
import io.github.yansenchandra.converter.engine.Rational
import io.github.yansenchandra.converter.engine.convertCurrency
import io.github.yansenchandra.converter.engine.display
import java.io.File

/** Home-screen widget: "1 SGD = 3.14 MYR" for the first favourite pair (else the current pair). */
class ConverterWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val (title, subtitle) = content(context)
        provideContent {
            GlanceTheme { WidgetBody(title, subtitle) }
        }
    }

    /** Reads the same master list, rates cache and saved pairs the app uses. */
    private fun content(context: Context): Pair<String, String> {
        val empty = context.getString(R.string.widget_empty)
        val pair = Store(context).widgetPair() ?: return empty to ""
        return runCatching {
            if (pair.category == CURRENCY) {
                val rates = Rates.parse(File(context.filesDir, "rates.json").readText())
                val value = display(convertCurrency(Rational.ONE, pair.from, pair.to, rates)).text
                "1 ${pair.from} = $value ${pair.to}" to context.getString(R.string.widget_rates, rates.date)
            } else {
                val ml = MasterList.parse(context.assets.open("master-list.json").bufferedReader().use { it.readText() })
                val from = ml.units.getValue(pair.from)
                val to = ml.units.getValue(pair.to)
                val value = display(ml.convert(listOf(Rational.ONE to from.id), to.id)).text
                "1 ${from.symbol} = $value ${to.symbol}" to "${from.nameOne} → ${to.nameOther}"
            }
        }.getOrElse { empty to "" }
    }
}

@Composable
private fun WidgetBody(title: String, subtitle: String) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(16.dp)
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold),
            maxLines = 2,
        )
        if (subtitle.isNotEmpty()) {
            Text(subtitle, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp), maxLines = 1)
        }
    }
}

class ConverterWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ConverterWidget()
}
