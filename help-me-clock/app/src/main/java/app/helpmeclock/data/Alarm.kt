package app.helpmeclock.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The next alarm to show, already filtered to the clock app. */
data class NextAlarm(val triggerAt: Long, val showIntent: PendingIntent?)

object Alarms {
    /**
     * The package that handles "show alarms": Google Clock on Pixel, Samsung Clock on Galaxy.
     * Null when nothing (or only a chooser) handles it.
     */
    fun clockPackage(ctx: Context): String? = runCatching {
        val ri = ctx.packageManager.resolveActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS), PackageManager.MATCH_DEFAULT_ONLY)
        ri?.activityInfo?.packageName?.takeIf { it != "android" }
    }.getOrNull()

    /**
     * Android only reports the single earliest "alarm clock" from any app (sleep trackers and
     * calendars can set them too). We only show it when it comes from the clock app; if
     * something else is earlier, we show nothing rather than the wrong alarm.
     */
    fun next(ctx: Context): NextAlarm? {
        val info = ctx.getSystemService(AlarmManager::class.java)?.nextAlarmClock ?: return null
        val clock = clockPackage(ctx)
        val creator = info.showIntent?.creatorPackage
        if (clock != null && creator != clock) return null
        return NextAlarm(info.triggerTime, info.showIntent)
    }

    /** Who set the earliest alarm, for the logs: null when no alarm is set. */
    fun nextCreator(ctx: Context): String? =
        ctx.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.let { it.showIntent?.creatorPackage ?: "unknown app" }

    /** How the clock app is opened on tap; falls back to its "show alarms" screen. */
    fun openClockIntent(ctx: Context): Intent {
        val launch = clockPackage(ctx)?.let { ctx.packageManager.getLaunchIntentForPackage(it) }
        return (launch ?: Intent(AlarmClock.ACTION_SHOW_ALARMS)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/**
 * The alarm label: "7:30 AM" when it rings within 24 h, "Wed 7:30 AM" when later. Both stay
 * true until the alarm rings, and Android tells us when the next alarm changes, so the label
 * never needs a timer to update.
 */
fun alarmLabel(triggerAt: Long, now: Long, zone: ZoneId, locale: Locale, is24h: Boolean): String {
    val time = DateTimeFormatter.ofPattern(if (is24h) "H:mm" else "h:mm a", locale)
    val t = Instant.ofEpochMilli(triggerAt).atZone(zone)
    val label = t.format(time)
    return if (triggerAt - now < 24 * 60 * 60 * 1000L) label
    else t.format(DateTimeFormatter.ofPattern("EEE", locale)) + " " + label
}

/** "h:mm a" / "a h:mm" → "h:mm": the AM/PM marker is drawn separately, smaller. */
fun stripAmPm(pattern: String): String =
    pattern.replace("a", "").replace(" ", " ").replace(" ", " ").trim()
