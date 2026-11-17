package app.nimbus.weather.work

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
import app.nimbus.weather.data.AppLog
import app.nimbus.weather.data.Store
import app.nimbus.weather.data.WeatherRepository
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
        val force = inputData.getBoolean(KEY_FORCE, false)
        val reason = inputData.getString(KEY_REASON) ?: "scheduled"
        val r = WeatherRepository.refresh(applicationContext, force = force, reason = reason)
        return when {
            r.isSuccess -> Result.success()
            runAttemptCount < 2 -> Result.retry()
            else -> Result.failure()
        }
    }

    companion object {
        private const val PERIODIC = "nimbus-periodic"
        private const val NOW = "nimbus-now"
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
            CoroutineScope(Dispatchers.Default).launch {
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
