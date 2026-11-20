package app.helpmeclock.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import app.helpmeclock.BuildConfig
import app.helpmeclock.data.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Widget redraws, the standard Glance way: [ClockWidget.update] for one widget, `updateAll` for
 * all of them. Glance composes in its own short session, keeps layout ids stable across updates
 * (so the launcher updates the widget in place) and handles resizes itself.
 */
object WidgetRenderer {
    private val _changes = MutableStateFlow(0L)

    /**
     * Ticks on every redraw request. The widget content combines it with its stored settings, so
     * inputs that aren't stored data (the next alarm, the time zone) also recompose a session
     * that's still open.
     */
    val changes: StateFlow<Long> get() = _changes

    fun ids(ctx: Context): IntArray =
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, ClockWidgetReceiver::class.java))

    suspend fun renderAll(ctx: Context) {
        _changes.update { it + 1 }
        runCatching { ClockWidget().updateAll(ctx) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; AppLog.e(ctx, "Widget", "Widget update failed", it) }
    }

    suspend fun render(ctx: Context, appWidgetId: Int) {
        _changes.update { it + 1 }
        runCatching { ClockWidget().update(ctx, GlanceAppWidgetManager(ctx).getGlanceIdBy(appWidgetId)) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; AppLog.e(ctx, "Widget", "Update of widget $appWidgetId failed", it) }
    }

    /**
     * Clears generated picker previews an older version published, so the picker uses the static
     * previewLayout. Once per version: on Android 15+ this changes the provider's info, and a
     * launcher resets placed widgets to their initial layout when that happens.
     */
    fun removeGeneratedPreviews(ctx: Context) {
        if (Build.VERSION.SDK_INT < 35) return
        val prefs = ctx.getSharedPreferences("meta", Context.MODE_PRIVATE)
        if (prefs.getInt("previews_removed_v", 0) == BuildConfig.VERSION_CODE) return
        val mgr = AppWidgetManager.getInstance(ctx)
        val provider = ComponentName(ctx, ClockWidgetReceiver::class.java)
        runCatching { mgr.removeWidgetPreview(provider, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) }
        runCatching { mgr.removeWidgetPreview(provider, AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD) }
        prefs.edit().putInt("previews_removed_v", BuildConfig.VERSION_CODE).apply()
    }
}
