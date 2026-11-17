package app.nimbus.weather.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import app.nimbus.weather.data.AppLog
import app.nimbus.weather.data.Store
import app.nimbus.weather.work.RefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

private fun BroadcastReceiver.async(block: suspend () -> Unit) {
    val pending = goAsync()
    receiverScope.launch { try { block() } finally { pending.finish() } }
}

/** Plain AppWidgetProvider: renders through [WidgetRenderer] instead of Glance sessions. */
class WeatherWidgetReceiver : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) = async {
        AppLog.i(context, "Widget", "System update for ${appWidgetIds.size} widget(s) (added, restored or rebooted)")
        appWidgetIds.forEach { WidgetRenderer.render(context, it) }
        // Added / restored / rebooted: ensure the schedule exists and data isn't stale.
        // Not forced, so it's a no-op when the cache is fresh.
        RefreshWorker.scheduleNow(context)
        RefreshWorker.refreshNow(context, force = false, reason = "widget update")
    }

    /** Resized: re-layout for the new size (no network). */
    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) = async {
        WidgetRenderer.render(context, appWidgetId)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) = async {
        AppLog.i(context, "Widget", "Removed ${appWidgetIds.size} widget(s)")
        val store = Store(context)
        appWidgetIds.forEach { store.deleteWidget(it) }
    }

    override fun onDisabled(context: Context) {
        RefreshWorker.cancel(context)
    }
}

/** Refresh button / tap-to-refresh: spinner right away, then an expedited fetch. */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        AppLog.i(context, "Widget", "Refresh tapped")
        // Only show the spinner when we're online; offline the job waits for a network and a
        // spinner would sit there indefinitely.
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        if (cm?.activeNetwork != null) {
            Store(context).setRefreshing(true)
            WidgetRenderer.renderAll(context)
        }
        RefreshWorker.refreshNow(context, force = true, reason = "refresh button")
    }
}

/**
 * Re-renders (no network) when the clock, time zone, 12/24h setting or locale changes so hour
 * labels stay right. These broadcasts are exempt from implicit-broadcast limits.
 */
class TimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = async { WidgetRenderer.renderAll(context) }
}
