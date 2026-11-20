package app.helpmestocks.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import app.helpmestocks.R
import app.helpmestocks.data.AppLog
import app.helpmestocks.data.StocksRepository
import app.helpmestocks.work.RefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The refresh button's work, as a short foreground service (owner-approved 2026-11-19).
 *
 * Why: an idle app has no network on Android 15+ (Doze and the background-app restriction), a
 * widget broadcast doesn't lift that, and jobs ran minutes after the tap on a Pixel 11 Pro on 5G.
 * Android lets an app start a foreground service when the user taps its widget, and a foreground
 * service gets the network right away. It runs for a second or two, never opens a window (so
 * home screen swipes keep working), and the notification Android requires is never shown: the
 * app has no notification permission.
 */
class RefreshService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        lastStartId = startId
        if (work?.isActive == true) {
            AppLog.i(this, "Widget", "Refresh already in progress; ignoring extra tap")
            return START_NOT_STICKY
        }
        val tapped = intent?.getLongExtra(EXTRA_TAPPED_AT, 0L)?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime()
        val app = applicationContext
        work = scope.launch {
            try {
                val waited = waitForNetwork(app, NETWORK_WAIT_MS)
                if (waited < 0) {
                    AppLog.w(app, "Widget", "Refresh tapped. No network; refreshing automatically when you're back online")
                    RefreshWorker.refreshNow(app, force = true, reason = "refresh button (offline)")
                    return@launch
                }
                AppLog.i(app, "Widget", "Refresh tapped (network ready ${SystemClock.elapsedRealtime() - tapped} ms after the tap)")
                val result = withTimeoutOrNull(BUDGET_MS) { StocksRepository.refresh(app, force = true, reason = "refresh button") }
                if (result == null || result.isFailure) {
                    AppLog.w(app, "Widget", if (result == null) "Refresh took too long; retrying in the background" else "Refresh failed; retrying in the background")
                    RefreshWorker.refreshNow(app, force = true, reason = "refresh button retry")
                } else {
                    // Glance applies the final redraw in its own session; stay up a moment so it
                    // lands before Android puts the app back to sleep (else the spinner sticks).
                    delay(SETTLE_MS)
                }
                AppLog.i(app, "Widget", "Refresh tap handled in ${SystemClock.elapsedRealtime() - tapped} ms")
            } finally {
                // The latest start, so a tap that arrives as this one finishes isn't cut off.
                stopSelf(lastStartId)
            }
        }
        return START_NOT_STICKY
    }

    /** Android 14 stops a short service after a few minutes; ours never gets close. */
    @Deprecated("Android 15+ calls the two-argument version")
    override fun onTimeout(startId: Int) {
        AppLog.w(this, "Widget", "Refresh service timed out; stopping")
        work?.cancel()
        stopSelf()
    }

    /** Android 15+ stops a short service after a few minutes; ours never gets close. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLog.w(this, "Widget", "Refresh service timed out; stopping")
        work?.cancel()
        stopSelf()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Refresh", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Required by Android while the refresh button runs. Never shown: the app has no notification permission."
                    setShowBadge(false)
                },
            )
        }
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Refreshing")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        else startForeground(NOTIFICATION_ID, n)
    }

    companion object {
        private const val EXTRA_TAPPED_AT = "tapped_at"
        private const val CHANNEL = "refresh"
        private const val NOTIFICATION_ID = 1
        private const val NETWORK_WAIT_MS = 3_000L
        private const val BUDGET_MS = 8_000L
        private const val SETTLE_MS = 1_500L

        /** Not tied to the service: stopping it must not cut a refresh's cleanup short. */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private var work: Job? = null
        @Volatile private var lastStartId = 0

        /** Milliseconds until the app has a network (it follows the foreground state with a short delay), or -1. */
        private suspend fun waitForNetwork(ctx: Context, maxMs: Long): Long {
            val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
            val start = SystemClock.elapsedRealtime()
            while (true) {
                if (cm?.activeNetwork != null) return SystemClock.elapsedRealtime() - start
                if (SystemClock.elapsedRealtime() - start >= maxMs) return -1
                delay(100)
            }
        }

        /** Starts the refresh from a widget tap. False if Android refused. */
        fun start(ctx: Context): Boolean = runCatching {
            ctx.startForegroundService(Intent(ctx, RefreshService::class.java).putExtra(EXTRA_TAPPED_AT, SystemClock.elapsedRealtime()))
        }.onFailure { AppLog.w(ctx, "Widget", "Android didn't allow the refresh service", it) }.isSuccess
    }
}
