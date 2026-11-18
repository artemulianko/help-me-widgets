package app.helpmeclock.widget

import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import app.helpmeclock.data.Alarms
import app.helpmeclock.data.AppLog
import app.helpmeclock.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Date

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

private fun BroadcastReceiver.async(block: suspend () -> Unit) {
    val pending = goAsync()
    receiverScope.launch { try { block() } finally { pending.finish() } }
}

/** Plain AppWidgetProvider: renders through [WidgetRenderer] instead of Glance sessions. */
class ClockWidgetReceiver : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) = async {
        AppLog.i(context, "Widget", "System update for ${appWidgetIds.size} widget(s) (added, restored or rebooted)")
        appWidgetIds.forEach { WidgetRenderer.render(context, it) }
    }

    /** Resized: re-layout for the new size. */
    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) = async {
        WidgetRenderer.render(context, appWidgetId)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) = async {
        AppLog.i(context, "Widget", "Removed ${appWidgetIds.size} widget(s)")
        val store = Store(context)
        appWidgetIds.forEach { store.deleteWidget(it) }
    }
}

/**
 * The only things that change what the clock shows besides the time itself: the next alarm,
 * the clock or time zone, 12/24-hour, and the language (date formats). The time keeps ticking
 * on its own. All of these broadcasts are exempt from Android's implicit-broadcast limits.
 */
class ClockEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = async {
        when (intent.action) {
            AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED -> AppLog.i(context, "Alarm", describeNextAlarm(context))
            Intent.ACTION_TIMEZONE_CHANGED -> AppLog.i(context, "System", "Time zone changed")
            Intent.ACTION_LOCALE_CHANGED -> AppLog.i(context, "System", "Language changed")
        }
        if (WidgetRenderer.ids(context).isNotEmpty()) WidgetRenderer.renderAll(context)
    }
}

/** "Next alarm: Wed 07:30 (shown)" / "No alarm set" / "… from another app (hidden)". */
fun describeNextAlarm(context: Context): String {
    val am = context.getSystemService(AlarmManager::class.java)?.nextAlarmClock ?: return "No alarm set"
    val fmt = DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), if (DateFormat.is24HourFormat(context)) "EEEHm" else "EEEhm")
    val whenText = DateFormat.format(fmt, Date(am.triggerTime))
    val creator = Alarms.nextCreator(context)
    return if (Alarms.next(context) != null) "Next alarm: $whenText (shown)"
    else "Next alarm: $whenText, set by $creator, not the clock app (hidden)"
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
                // Android also re-sends BOOT_COMPLETED to an app leaving the force-stopped state;
                // the global boot counter tells a real reboot apart.
                val boots = android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
                val prefs = context.getSharedPreferences("meta", Context.MODE_PRIVATE)
                val stored = prefs.getInt("boot_count", -2)
                val realBoot = if (stored == -2) android.os.SystemClock.elapsedRealtime() < 15 * 60_000L else boots != stored
                prefs.edit().putInt("boot_count", boots).apply()
                AppLog.i(context, "System", if (realBoot) "Phone restarted" else "App restarted after being stopped (install, update or force stop)")
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> AppLog.i(context, "System", "App updated to v${app.helpmeclock.BuildConfig.VERSION_NAME}")
        }
        WidgetRenderer.renderAll(context)
    }
}
