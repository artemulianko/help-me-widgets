package app.helpmeweather.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import app.helpmeweather.data.AppLog
import app.helpmeweather.data.Store
import app.helpmeweather.work.RefreshWorker
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
        } else {
            AppLog.w(context, "Widget", "No network: refresh will run automatically when you're back online")
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

/** Logs phone reboots and app updates, and re-renders widgets so they're current right away. */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = async {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                // Android also re-sends BOOT_COMPLETED to an app leaving the force-stopped state;
                // the global boot counter tells a real reboot apart.
                val boots = android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
                val prefs = context.getSharedPreferences("meta", Context.MODE_PRIVATE)
                // Real reboot: the boot counter moved, or (first run, no stored count) the phone
                // has only been up a few minutes.
                val stored = prefs.getInt("boot_count", -2)
                val realBoot = if (stored == -2) android.os.SystemClock.elapsedRealtime() < 15 * 60_000L else boots != stored
                if (realBoot) {
                    prefs.edit().putInt("boot_count", boots).apply()
                    AppLog.i(context, "System", "Phone restarted; background refresh resumes automatically")
                } else {
                    prefs.edit().putInt("boot_count", boots).apply()
                    AppLog.i(context, "System", "App restarted after being stopped (install, update or force stop)")
                }
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> AppLog.i(context, "System", "App updated to v${app.helpmeweather.BuildConfig.VERSION_NAME}")
        }
        WidgetRenderer.renderAll(context)
        RefreshWorker.scheduleNow(context)
    }
}
