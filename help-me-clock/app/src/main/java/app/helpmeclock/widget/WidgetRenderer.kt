package app.helpmeclock.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Build
import app.helpmeclock.data.AppLog
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.composeForPreview
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Session-free widget rendering.
 *
 * Glance's default update path hosts a live composition in a WorkManager job and keeps it
 * alive (holding a job wake lock) for ~45 s after every render, waiting for more state.
 * The clock only re-renders when something it shows changes (settings, next alarm, locale,
 * time zone); the launcher ticks the time itself through TextClock. One short render, no job,
 * no lingering wake lock.
 */
object WidgetRenderer {
    private const val TAG = "HelpMeClockRender"
    private val lock = Mutex() // compose() must not run concurrently for the same id

    fun ids(ctx: Context): IntArray =
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, ClockWidgetReceiver::class.java))

    suspend fun renderAll(ctx: Context) {
        ids(ctx).forEach { render(ctx, it) } // only failures are logged; renders are routine
    }

    suspend fun render(ctx: Context, appWidgetId: Int) = lock.withLock {
        try {
            val mgr = AppWidgetManager.getInstance(ctx)
            val options = mgr.getAppWidgetOptions(appWidgetId)
            val glanceId = GlanceAppWidgetManager(ctx).getGlanceIdBy(appWidgetId)
            // With SizeMode.Exact and no explicit size, Glance composes one layout per size the
            // launcher reports (portrait/landscape, foldables) and returns a sized RemoteViews map,
            // so the host can switch layouts without waking us.
            val t0 = android.os.SystemClock.elapsedRealtime()
            val views = ClockWidget().compose(ctx, glanceId, options)
            val t1 = android.os.SystemClock.elapsedRealtime()
            mgr.updateAppWidget(appWidgetId, views)
            // Debug only (never in the Logs screen): render cost, for the widget-perf-check skill.
            if (app.helpmeclock.BuildConfig.DEBUG) Log.d(TAG, "render $appWidgetId: compose ${t1 - t0} ms, update ${android.os.SystemClock.elapsedRealtime() - t1} ms")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // a newer refresh replaced this one; not a failure
        } catch (e: Exception) {
            AppLog.e(ctx, "Widget", "Render of widget $appWidgetId failed", e)
        }
    }

    /**
     * The picker uses the static `widget_preview` layout (live TextClocks, mirrors the 4×1).
     * Generated previews, built from the widget's TextClock views, came out broken on a real
     * Pixel, so any published by 0.1.0 are removed (Android 15+).
     */
    fun removeGeneratedPreviews(ctx: Context) {
        if (Build.VERSION.SDK_INT < 35) return
        val mgr = AppWidgetManager.getInstance(ctx)
        val provider = ComponentName(ctx, ClockWidgetReceiver::class.java)
        runCatching { mgr.removeWidgetPreview(provider, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) }
        runCatching { mgr.removeWidgetPreview(provider, AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD) }
    }
}
