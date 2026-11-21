package app.helpmeweather.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.helpmeweather.data.AppLog
import app.helpmeweather.data.Store
import app.helpmeweather.data.WeatherRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * All network work goes through WorkManager so the OS can batch it with other apps' jobs,
 * respect Doze/App Standby and only run with a network. No alarms, no wake locks, no services.
 */
class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val force = inputData.getBoolean(KEY_FORCE, false)
        val reason = inputData.getString(KEY_REASON) ?: "scheduled"
        val attempt = runAttemptCount + 1
        AppLog.i(ctx, "Job", "Started: $reason${if (attempt > 1) " (attempt $attempt)" else ""}")
        try {
            // No spinner from a background job: Android may run it minutes after the tap, and a
            // redraw it sends can be held back too, which once left the spinner up until the app
            // was opened (Pixel 11 Pro, 1.0.0).
            val r = WeatherRepository.refresh(ctx, force = force, reason = reason, spinner = false)
            // Glance applies the redraw in its own session; stay running a moment only when we
            // actually sent a redraw so Android doesn't freeze the app before it lands.
            if (r.getOrDefault(false)) kotlinx.coroutines.delay(SETTLE_MS)
            return when {
                r.isSuccess -> Result.success()
                runAttemptCount < 2 -> {
                    AppLog.w(ctx, "Job", "Failed, Android will retry with backoff (attempt $attempt of 3)")
                    Result.retry()
                }
                else -> {
                    AppLog.e(ctx, "Job", "Failed after 3 attempts; next try at the next scheduled refresh")
                    Result.failure()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (isStopped) AppLog.w(ctx, "Job", "Stopped early by Android (${stopReasonName()}); it will run again later")
            throw e
        }
    }

    private fun stopReasonName(): String = when (if (android.os.Build.VERSION.SDK_INT >= 31) stopReason else -1) {
        android.app.job.JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "network lost"
        android.app.job.JobParameters.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW -> "battery low"
        android.app.job.JobParameters.STOP_REASON_TIMEOUT -> "ran too long"
        android.app.job.JobParameters.STOP_REASON_DEVICE_STATE -> "device state (Doze)"
        android.app.job.JobParameters.STOP_REASON_APP_STANDBY -> "app standby"
        android.app.job.JobParameters.STOP_REASON_QUOTA -> "background quota"
        android.app.job.JobParameters.STOP_REASON_SYSTEM_PROCESSING -> "system busy"
        android.app.job.JobParameters.STOP_REASON_CANCELLED_BY_APP -> "replaced by a newer refresh"
        android.app.job.JobParameters.STOP_REASON_USER -> "stopped by you"
        else -> "reason ${if (android.os.Build.VERSION.SDK_INT >= 31) stopReason else "unknown"}"
    }

    companion object {
        /** How long a job stays alive after its redraw so Glance can apply it. */
        private const val SETTLE_MS = 3_000L
        private const val PERIODIC = "periodic-refresh"
        private const val NOW = "refresh-now"
        private const val KEY_FORCE = "force"
        private const val KEY_REASON = "reason"

        private suspend fun constraints(ctx: Context): Constraints {
            val app = Store(ctx).appNow()
            return Constraints.Builder()
                .setRequiredNetworkType(if (app.unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()
        }

        /** (Re)creates the periodic job from settings. Idempotent — keeps the existing schedule if unchanged. */
        fun schedule(ctx: Context) {
            CoroutineScope(Dispatchers.Default).launch { scheduleNow(ctx) }
        }

        suspend fun scheduleNow(ctx: Context) {
            val wm = WorkManager.getInstance(ctx)
            val minutes = Store(ctx).appNow().refreshMinutes
            val prefs = ctx.getSharedPreferences("meta", Context.MODE_PRIVATE)
            fun logOnce(sig: String, msg: String) {
                if (prefs.getString("sched", null) != sig) { prefs.edit().putString("sched", sig).apply(); AppLog.i(ctx, "Schedule", msg) }
            }
            if (minutes <= 0 || WeatherRepository.widgetIds(ctx).isEmpty()) {
                wm.cancelUniqueWork(PERIODIC)
                if (minutes <= 0) logOnce("off", "Background refresh off (manual only)")
                else logOnce("none", "No widgets: background refresh stopped")
                return
            }
            val c = Constraints.Builder()
                .setRequiredNetworkType(if (Store(ctx).appNow().unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                // Skip background refreshes when the battery is low; manual refresh still works.
                .setRequiresBatteryNotLow(true)
                .build()
            val interval = minutes.coerceAtLeast(15).toLong()
            val req = PeriodicWorkRequestBuilder<RefreshWorker>(interval, TimeUnit.MINUTES, (interval / 4).coerceAtLeast(5), TimeUnit.MINUTES)
                .setConstraints(c)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .addTag(PERIODIC)
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
            val wifi = Store(ctx).appNow().unmeteredOnly
            logOnce("$interval/$wifi", "Background refresh every $interval min${if (wifi) " on Wi‑Fi" else ""}, skipped on low battery")
        }

        fun refreshNow(ctx: Context, force: Boolean, reason: String = if (force) "manual" else "widget update") {
            CoroutineScope(Dispatchers.IO).launch { // getWorkInfos…().get() blocks
                // A refresh already downloading wins: extra taps would only cancel it and spend quota.
                val running = runCatching {
                    WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(NOW).get()
                        .any { it.state == androidx.work.WorkInfo.State.RUNNING }
                }.getOrDefault(false)
                if (running) {
                    if (force) AppLog.i(ctx, "Job", "Refresh already in progress; ignoring extra tap")
                    return@launch
                }
                val req = OneTimeWorkRequestBuilder<RefreshWorker>()
                    .setConstraints(constraints(ctx))
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setInputData(workDataOf(KEY_FORCE to force, KEY_REASON to reason))
                    .build()
                // A forced refresh replaces a queued one; a passive one never piles up.
                WorkManager.getInstance(ctx).enqueueUniqueWork(
                    NOW, if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req,
                )
            }
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC)
        }
    }
}
