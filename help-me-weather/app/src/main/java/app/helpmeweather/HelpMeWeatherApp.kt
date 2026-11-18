package app.helpmeweather

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import app.helpmeweather.data.AppLog

/**
 * Logs process lifecycle for the in-app Logs screen: each process start, why the previous
 * process ended (Android's exit reasons: low memory, crash, update, …) and crashes.
 */
class HelpMeWeatherApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppLog.i(this, "App", "Process started (v${BuildConfig.VERSION_NAME})")
        logPreviousExit()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { AppLog.crash(this, "Crash", "Uncaught on ${thread.name}", error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Android keeps the reasons recent processes died; log any we haven't reported yet. */
    private fun logPreviousExit() = runCatching {
        val am = getSystemService(ActivityManager::class.java) ?: return@runCatching
        val prefs = getSharedPreferences("meta", Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong("exit_ts", 0L)
        val exits = am.getHistoricalProcessExitReasons(packageName, 0, 5).filter { it.timestamp > lastSeen }
        exits.sortedBy { it.timestamp }.forEach { e ->
            val text = "Previous process ended: ${reasonName(e.reason)}" + (e.description?.let { " ($it)" } ?: "")
            if (e.reason == ApplicationExitInfo.REASON_CRASH || e.reason == ApplicationExitInfo.REASON_ANR ||
                e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
            ) AppLog.w(this, "App", text) else AppLog.i(this, "App", text)
        }
        exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong("exit_ts", it).apply() }
    }

    private fun reasonName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "finished normally"
        ApplicationExitInfo.REASON_SIGNALED -> "stopped by a signal"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "killed for low memory"
        ApplicationExitInfo.REASON_CRASH -> "crashed"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crashed (native)"
        ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission changed"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "killed for excessive resource use"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by you (force stop / update)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "a dependency died"
        ApplicationExitInfo.REASON_OTHER -> "ended by the system"
        ApplicationExitInfo.REASON_FREEZER -> "frozen too long"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "app state changed"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "app updated"
        else -> "reason $r"
    }
}
