package app.helpmestocks.ui

import android.app.Activity
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import app.helpmestocks.data.AppLog
import app.helpmestocks.data.StocksRepository
import app.helpmestocks.work.RefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The widget's refresh button. An invisible, untouchable window that stays only while the
 * refresh runs (about a second), then closes.
 *
 * Why an activity: in the background Android 15+ blocks the network for this app (Doze, and
 * "app background" blocking of cached apps). A widget tap handled by a broadcast doesn't lift
 * that block, and the job it falls back to can be held back for many minutes (seen on a Pixel
 * 11 Pro: taps at 14:57, job at 15:11). While one of our activities is on screen the app counts
 * as foreground, so the network is open immediately. No notification, no service.
 */
class RefreshActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Invisible and click-through: touches go to the home screen underneath.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        // 1x1 px in a corner: floating, so it never repaints the system bars.
        window.setLayout(1, 1)
        window.setGravity(android.view.Gravity.TOP or android.view.Gravity.START)
        noAnimation()
        if (savedInstanceState != null) { finish(); return } // recreated: the refresh already ran
        val app = applicationContext
        AppLog.i(app, "Widget", "Refresh tapped")
        scope.launch {
            try {
                val online = app.getSystemService(ConnectivityManager::class.java)?.activeNetwork != null
                if (!online) {
                    AppLog.w(app, "Widget", "No network: refresh will run automatically when you're back online")
                    RefreshWorker.refreshNow(app, force = true, reason = "refresh button (offline)")
                    return@launch
                }
                val result = withTimeoutOrNull(BUDGET_MS) {
                    StocksRepository.refresh(app, force = true, reason = "refresh button")
                }
                if (result == null || result.isFailure) {
                    AppLog.w(app, "Widget", if (result == null) "Refresh took too long; retrying in the background" else "Refresh failed; retrying in the background")
                    RefreshWorker.refreshNow(app, force = true, reason = "refresh button retry")
                }
            } finally {
                withContext(Dispatchers.Main) { if (!isFinishing) finish() }
            }
        }
    }

    override fun finish() {
        super.finish()
        noAnimation()
    }

    @Suppress("DEPRECATION")
    private fun noAnimation() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            overridePendingTransition(0, 0)
        }
    }

    private companion object {
        /** Not tied to the activity: closing the window early must not cancel a refresh. */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        const val BUDGET_MS = 8_000L
    }
}
