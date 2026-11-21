package app.helpmecalendar.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import app.helpmecalendar.data.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Widget redraws, the standard Glance way: [CalendarWidget.update] for one widget, `updateAll`
 * for all of them. Glance composes in its own short session, keeps layout ids stable across
 * updates and handles resizes itself.
 */
object WidgetRenderer {
    private val _changes = MutableStateFlow(0L)

    /** Ticks on every redraw request so live Glance sessions re-read CalendarContract. */
    val changes: StateFlow<Long> get() = _changes

    fun ids(ctx: Context): IntArray =
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, CalendarWidgetReceiver::class.java))

    suspend fun renderAll(ctx: Context) {
        _changes.update { it + 1 }
        runCatching { CalendarWidget().updateAll(ctx) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; AppLog.e(ctx, "Widget", "Widget update failed", it) }
    }

    suspend fun render(ctx: Context, appWidgetId: Int) {
        _changes.update { it + 1 }
        runCatching { CalendarWidget().update(ctx, GlanceAppWidgetManager(ctx).getGlanceIdBy(appWidgetId)) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; AppLog.e(ctx, "Widget", "Update of widget $appWidgetId failed", it) }
    }
}
