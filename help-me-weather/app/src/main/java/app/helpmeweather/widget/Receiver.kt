package app.helpmeweather.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import app.helpmeweather.data.AppLog
import app.helpmeweather.data.Store
import app.helpmeweather.data.WeatherRepository
import app.helpmeweather.work.RefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The widget, the standard Glance way: Glance's receiver hosts the widget, handles placement,
 * resizes and removal, and every redraw goes through [WeatherWidget.update] / `updateAll`.
 */
class WeatherWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = WeatherWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        AppLog.i(context, "Widget", "System update for ${appWidgetIds.size} widget(s) (added, restored or rebooted)")
        // Make sure the periodic schedule exists. No one-time work here (Google's widget skill:
        // a finished one-time job toggles WorkManager's RescheduleReceiver, Android broadcasts
        // PACKAGE_CHANGED and the launcher calls onUpdate again).
        RefreshWorker.schedule(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        AppLog.i(context, "Widget", "Removed ${appWidgetIds.size} widget(s)")
        receiverScope.launch { val store = Store(context); appWidgetIds.forEach { store.deleteWidget(it) } }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        RefreshWorker.cancel(context)
    }
}

/** Widget refresh button / tap-to-refresh. */
fun refreshAction(): Action = actionRunCallback<RefreshAction>()

/**
 * The refresh button: starts [RefreshService], a short foreground service that gets the network
 * right away even when the phone is idle. If Android refuses (it shouldn't after a widget tap),
 * refreshes inline when there is a network, else hands off to a background job.
 */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        if (RefreshService.start(context)) return
        val online = context.getSystemService(android.net.ConnectivityManager::class.java)?.activeNetwork != null
        if (!online) {
            AppLog.w(context, "Widget", "Refresh tapped. No network for the app right now; refreshing in the background as soon as Android allows")
            RefreshWorker.refreshNow(context, force = true, reason = "refresh button (offline)")
            return
        }
        AppLog.i(context, "Widget", "Refresh tapped")
        val result = withTimeoutOrNull(8_000) { WeatherRepository.refresh(context, force = true, reason = "refresh button") }
        if (result == null || result.isFailure) RefreshWorker.refreshNow(context, force = true, reason = "refresh button retry")
    }
}

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

private fun BroadcastReceiver.async(block: suspend () -> Unit) {
    val pending = goAsync()
    receiverScope.launch { try { block() } finally { pending.finish() } }
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
