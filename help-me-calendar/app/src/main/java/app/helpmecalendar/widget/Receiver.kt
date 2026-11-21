package app.helpmecalendar.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.helpmecalendar.data.AppLog
import app.helpmecalendar.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

private fun BroadcastReceiver.async(block: suspend () -> Unit) {
    val pending = goAsync()
    receiverScope.launch { try { block() } finally { pending.finish() } }
}

/**
 * The widget, the standard Glance way: Glance's receiver hosts the widget, handles placement,
 * resizes and removal, and every redraw goes through [CalendarWidget.update] / `updateAll`.
 */
class CalendarWidgetReceiver : androidx.glance.appwidget.GlanceAppWidgetReceiver() {
    override val glanceAppWidget = CalendarWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // updatePeriodMillis (30 min, non-wakeup) re-renders from CalendarContract so past events
        // roll off automatically; only log when the widget set changes so routine renders stay quiet.
        val prefs = context.getSharedPreferences("meta", Context.MODE_PRIVATE)
        val sig = appWidgetIds.sorted().joinToString(",")
        if (prefs.getString("widget_ids", null) != sig) {
            prefs.edit().putString("widget_ids", sig).apply()
            AppLog.i(context, "Widget", "System update for ${appWidgetIds.size} widget(s) (added, restored or rebooted)")
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        AppLog.i(context, "Widget", "Removed ${appWidgetIds.size} widget(s)")
        receiverScope.launch { val store = Store(context); appWidgetIds.forEach { store.deleteWidget(it) } }
    }
}

/** Re-renders when CalendarContract changes, the day rolls over, or time zone / locale changes. */
class CalendarEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = async {
        when (intent.action) {
            Intent.ACTION_PROVIDER_CHANGED -> AppLog.i(context, "Calendar", "Calendar updated")
            Intent.ACTION_DATE_CHANGED -> AppLog.i(context, "Calendar", "Day changed")
            Intent.ACTION_TIMEZONE_CHANGED -> AppLog.i(context, "System", "Time zone changed")
            Intent.ACTION_LOCALE_CHANGED -> AppLog.i(context, "System", "Language changed")
        }
        if (WidgetRenderer.ids(context).isNotEmpty()) WidgetRenderer.renderAll(context)
    }
}

/** Logs phone reboots and app updates, and re-renders widgets so they're current right away. */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        handle(context, intent)
    }

    private fun handle(context: Context, intent: Intent) = async {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                val boots = android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
                val prefs = context.getSharedPreferences("meta", Context.MODE_PRIVATE)
                val stored = prefs.getInt("boot_count", -2)
                val realBoot = if (stored == -2) android.os.SystemClock.elapsedRealtime() < 15 * 60_000L else boots != stored
                prefs.edit().putInt("boot_count", boots).apply()
                AppLog.i(context, "System", if (realBoot) "Phone restarted" else "App restarted after being stopped (install, update or force stop)")
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> AppLog.i(context, "System", "App updated to v${app.helpmecalendar.BuildConfig.VERSION_NAME}")
        }
        WidgetRenderer.renderAll(context)
    }
}
