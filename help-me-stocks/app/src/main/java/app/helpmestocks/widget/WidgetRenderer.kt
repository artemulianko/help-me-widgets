package app.helpmestocks.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Build
import app.helpmestocks.data.AppLog
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
 * This widget only displays data, so we compose once to RemoteViews and hand them to
 * AppWidgetManager: one short render, no job, no lingering wake lock.
 */
object WidgetRenderer {
    private const val TAG = "HelpMeStocksRender"
    private val lock = Mutex() // compose() must not run concurrently for the same id

    fun ids(ctx: Context): IntArray =
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, StocksWidgetReceiver::class.java))

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
            val views = StocksWidget().compose(ctx, glanceId, options)
            mgr.updateAppWidget(appWidgetId, views)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // a newer refresh replaced this one; not a failure
        } catch (e: Exception) {
            AppLog.e(ctx, "Widget", "Render of widget $appWidgetId failed", e)
        }
    }

    /** Generated widget-picker previews (Android 15+), from sample data. */
    suspend fun publishPreviews(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 35) return false
        val mgr = AppWidgetManager.getInstance(ctx)
        val provider = ComponentName(ctx, StocksWidgetReceiver::class.java)
        val info = mgr.installedProviders.firstOrNull { it.provider == provider }
        return listOf(AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN, AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD).all { cat ->
            runCatching { mgr.setWidgetPreview(provider, cat, StocksWidget().composeForPreview(ctx, cat, info)) }
                .onFailure { Log.w(TAG, "preview failed", it) }.getOrDefault(false)
        }
    }

}
