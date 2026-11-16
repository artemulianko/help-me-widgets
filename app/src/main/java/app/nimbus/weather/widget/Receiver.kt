package app.nimbus.weather.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import app.nimbus.weather.data.Store
import app.nimbus.weather.work.RefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WeatherWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeatherWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // Added / restored / rebooted: make sure the schedule exists and data isn't stale.
        // Not forced, so this is a no-op when the cache is fresh.
        RefreshWorker.schedule(context)
        RefreshWorker.refreshNow(context, force = false)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val pending = goAsync()
        scope.launch {
            try {
                val store = Store(context)
                appWidgetIds.forEach { store.deleteWidget(it) }
            } finally {
                pending.finish()
            }
        }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        RefreshWorker.cancel(context)
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

/** Refresh button / tap-to-refresh. Shows the spinner immediately, then runs an expedited fetch. */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Store(context).setRefreshing(true)
        WeatherWidget().updateAll(context)
        RefreshWorker.refreshNow(context, force = true)
    }
}

/**
 * Re-renders (no network) when the clock, timezone or 12/24h setting changes, so hour labels
 * stay correct. These broadcasts are exempt from implicit-broadcast limits.
 */
class TimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try { WeatherWidget().updateAll(context) } finally { pending.finish() }
        }
    }
}
