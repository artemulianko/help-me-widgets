package app.helpmecalendar.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
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
import app.helpmecalendar.data.CalendarEvent
import app.helpmecalendar.data.Calendars
import app.helpmecalendar.data.Store
import app.helpmecalendar.data.TapAction
import app.helpmecalendar.data.WidgetConfig
import app.helpmecalendar.ui.MainActivity
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

data class WidgetState(
    val cfg: WidgetConfig,
    val hasPermission: Boolean,
    val events: List<CalendarEvent>,
    /** Day-of-month -> up to 3 distinct calendar colors for events on that day. */
    val monthEventColors: Map<Int, List<Int>>,
    val appWidgetId: Int,
    /** Wall-clock time (fixed in previews and tests). */
    val nowMs: Long = System.currentTimeMillis(),
)

class CalendarWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val flow = combine(Store(context).widget(appWidgetId), WidgetRenderer.changes) { cfg, _ ->
            loadState(context, cfg, appWidgetId)
        }
        val initial = flow.first()
        provideContent {
            val state by flow.collectAsState(initial)
            CalendarContent(state)
        }
    }

    companion object {
        fun loadState(ctx: Context, cfg: WidgetConfig, appWidgetId: Int, nowMs: Long = System.currentTimeMillis()): WidgetState {
            val hasPerm = Calendars.hasPermission(ctx)
            if (!hasPerm) return WidgetState(cfg, false, emptyList(), emptyMap(), appWidgetId, nowMs)
            val zone = ZoneId.systemDefault()
            val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            val events = Calendars.upcoming(ctx, cfg.calendarIds, cfg.daysAhead, cfg.showAllDay, nowMs, zone)
            val monthColors = Calendars.monthEventColors(ctx, cfg.calendarIds, YearMonth.from(today), cfg.showAllDay, zone)
            return WidgetState(cfg, true, events, monthColors, appWidgetId, nowMs)
        }
    }
}

/** Root: background, corner radius, and tap action. Layout is chosen from the real size. */
@Composable
fun CalendarContent(s: WidgetState) {
    val ctx = LocalContext.current
    val colors = widgetColors(s.cfg)
    var mod = GlanceModifier.fillMaxSize()
        .appWidgetBackground()
        .cornerRadius(android.R.dimen.system_app_widget_background_radius)
        .background(colors.bg)
    rootTap(ctx, s)?.let { mod = mod.clickable(it) }
    Box(modifier = mod) {
        WidgetLayouts(s, colors)
    }
}

fun rootTap(ctx: Context, s: WidgetState): Action? = when {
    !s.hasPermission -> actionStartActivity(
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_WIDGET_ID, s.appWidgetId),
    )
    s.cfg.tapAction == TapAction.NONE -> null
    s.cfg.tapAction == TapAction.CALENDAR -> actionStartActivity(Calendars.openCalendarIntent(s.nowMs))
    else -> actionStartActivity(
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_WIDGET_ID, s.appWidgetId),
    )
}

fun eventTap(ctx: Context, s: WidgetState, event: CalendarEvent): Action? = when (s.cfg.tapAction) {
    TapAction.CALENDAR -> if (event.id > 0L) actionStartActivity(Calendars.openEventIntent(event)) else rootTap(ctx, s)
    TapAction.APP -> rootTap(ctx, s)
    TapAction.NONE -> null
}
