package app.helpmestocks.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.composeForPreview
import androidx.glance.appwidget.updateAll
import app.helpmestocks.data.AppLog

/**
 * Widget redraws, the standard Glance way: [StocksWidget.update] for one widget, `updateAll` for
 * all of them. Glance composes in its own session, keeps layout ids stable across updates (so the
 * launcher updates the widget in place) and handles resizes itself. The widget's content observes
 * its data while a session is open, so a change mid-session is never missed.
 */
object WidgetRenderer {
    private const val TAG = "HelpMeStocksRender"

    fun ids(ctx: Context): IntArray =
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, StocksWidgetReceiver::class.java))

    suspend fun renderAll(ctx: Context) {
        runCatching { StocksWidget().updateAll(ctx) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; AppLog.e(ctx, "Widget", "Widget update failed", it) }
    }

    suspend fun render(ctx: Context, appWidgetId: Int) {
        runCatching { StocksWidget().update(ctx, GlanceAppWidgetManager(ctx).getGlanceIdBy(appWidgetId)) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; AppLog.e(ctx, "Widget", "Update of widget $appWidgetId failed", it) }
    }

    /** Generated widget-picker previews (Android 15+), from sample data. */
    suspend fun publishPreviews(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 35) return false
        val mgr = AppWidgetManager.getInstance(ctx)
        val provider = ComponentName(ctx, StocksWidgetReceiver::class.java)
        val info = mgr.installedProviders.firstOrNull { it.provider == provider }
        return listOf(AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN, AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD).all { cat ->
            runCatching { mgr.setWidgetPreview(provider, cat, StocksWidget().composeForPreview(ctx, cat, info)) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; if (app.helpmestocks.BuildConfig.DEBUG) Log.w(TAG, "preview failed", it) }
                .getOrDefault(false)
        }
    }
}
