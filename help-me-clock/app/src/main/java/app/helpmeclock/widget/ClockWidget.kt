package app.helpmeclock.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import app.helpmeclock.data.Alarms
import app.helpmeclock.data.NextAlarm
import app.helpmeclock.data.Store
import app.helpmeclock.data.TapAction
import app.helpmeclock.data.WidgetConfig
import app.helpmeclock.ui.MainActivity
import kotlinx.coroutines.flow.first

data class WidgetState(
    val cfg: WidgetConfig,
    val alarm: NextAlarm?,
    val appWidgetId: Int,
    /** Wall-clock time the alarm label is computed against (fixed in previews and tests). */
    val now: Long = System.currentTimeMillis(),
)

class ClockWidget : GlanceAppWidget() {

    // Exact: the numerals are sized to the real cell size.
    override val sizeMode: SizeMode = SizeMode.Exact


    /** Called by [WidgetRenderer] via compose(): read state once, render once. */
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // The content observes its inputs, so changes while a Glance session is open recompose it
        // (an update() during a live session doesn't call provideGlance again). The next alarm
        // isn't stored data: WidgetRenderer.changes ticks on every redraw request.
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val flow = kotlinx.coroutines.flow.combine(Store(context).widget(appWidgetId), WidgetRenderer.changes) { cfg, _ ->
            WidgetState(cfg, Alarms.next(context), appWidgetId)
        }
        val initial = flow.first()
        provideContent {
            val state by flow.collectAsState(initial)
            ClockContent(state)
        }
    }

}

/** Root: background, corner radius, and the tap action. Layout is chosen from the real size. */
@Composable
fun ClockContent(s: WidgetState) {
    val ctx = androidx.glance.LocalContext.current
    val colors = widgetColors(s.cfg)
    var mod = GlanceModifier.fillMaxSize()
        .appWidgetBackground()
        .cornerRadius(android.R.dimen.system_app_widget_background_radius)
        .background(colors.bg.glance())
    rootTap(ctx, s)?.let { mod = mod.clickable(it) }
    Box(modifier = mod) {
        WidgetLayouts(s, colors)
    }
}

fun rootTap(ctx: Context, s: WidgetState): Action? = when (s.cfg.tapAction) {
    TapAction.NONE -> null
    TapAction.CLOCK -> actionStartActivity(Alarms.openClockIntent(ctx))
    TapAction.APP -> actionStartActivity(
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_WIDGET_ID, s.appWidgetId),
    )
}
