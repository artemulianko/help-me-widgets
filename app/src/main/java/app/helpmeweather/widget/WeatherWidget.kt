package app.helpmeweather.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import app.helpmeweather.data.AppSettings
import app.helpmeweather.data.Place
import app.helpmeweather.data.Store
import app.helpmeweather.data.TapAction
import app.helpmeweather.data.WeatherData
import app.helpmeweather.data.WidgetConfig
import app.helpmeweather.ui.MainActivity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

data class WidgetState(
    val app: AppSettings,
    val cfg: WidgetConfig,
    val weather: WeatherData?,
    val refreshing: Boolean,
    val place: Place?,
    val appWidgetId: Int,
)

class WeatherWidget : GlanceAppWidget() {

    // Exact: we lay out continuously from the real size (column counts, bar widths, row counts).
    override val sizeMode: SizeMode = SizeMode.Exact

    // Generated picker previews at real Pixel spans (~97dp per column, ~105dp per row), so the
    // launcher shows the layout the widget will actually have at each size.
    override val previewSizeMode = SizeMode.Responsive(
        setOf(DpSize(190.dp, 210.dp), DpSize(387.dp, 105.dp), DpSize(387.dp, 212.dp), DpSize(387.dp, 316.dp)),
    )

    /** Called by [WidgetRenderer] via compose(): read state once, render once. */
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val state = stateFlow(Store(context), appWidgetId).first()
        provideContent { WeatherContent(state) }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val sample = SampleData.state(context)
        provideContent { WeatherContent(sample) }
    }

    companion object {
        @OptIn(ExperimentalCoroutinesApi::class)
        fun stateFlow(store: Store, appWidgetId: Int): Flow<WidgetState> =
            combine(store.app, store.widget(appWidgetId), store.refreshing) { a, c, r -> Triple(a, c, r) }
                .distinctUntilChanged()
                .flatMapLatest { (app, cfg, refreshing) ->
                    val place = cfg.location ?: app.deviceLocation
                    store.weather(place).map { WidgetState(app, cfg, it, refreshing, place, appWidgetId) }
                }
                .distinctUntilChanged()
    }
}

/** Root: background, corner radius, and the tap action. Layout is chosen from the real size. */
@Composable
fun WeatherContent(s: WidgetState) {
    val ctx = androidx.glance.LocalContext.current
    val colors = widgetColors(s.cfg)
    val tap = tapAction(ctx, s)
    var mod = GlanceModifier.fillMaxSize()
        .appWidgetBackground()
        .cornerRadius(android.R.dimen.system_app_widget_background_radius)
        .background(colors.bg)
    if (tap != null) mod = mod.clickable(tap)
    Box(modifier = mod) {
        WidgetLayouts(s, colors)
    }
}

fun tapAction(ctx: Context, s: WidgetState): Action? {
    if (s.place == null || s.weather == null && !s.refreshing) {
        return actionStartActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    return when (s.cfg.tapAction) {
        TapAction.NONE -> null
        TapAction.REFRESH -> actionRunCallback<RefreshAction>()
        TapAction.APP -> actionStartActivity(
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_WIDGET_ID, s.appWidgetId),
        )
        TapAction.PIXEL_WEATHER -> actionStartActivity(pixelWeatherIntent(ctx))
    }
}

/**
 * Opens Google's weather (the same screen the Pixel launcher's At a Glance opens):
 * the standalone Pixel Weather app when installed, else the Google app's weather page,
 * else this app.
 */
fun pixelWeatherIntent(ctx: Context): Intent {
    val pm = ctx.packageManager
    pm.getLaunchIntentForPackage(PIXEL_WEATHER_PKG)?.let { return it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    val gsa = Intent(Intent.ACTION_VIEW, Uri.parse("dynact://velour/weather/ProxyActivity"))
        .setComponent(ComponentName(GOOGLE_APP_PKG, "com.google.android.apps.gsa.velour.DynamicActivityTrampoline"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (pm.resolveActivity(gsa, 0) != null) return gsa
    return Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

const val PIXEL_WEATHER_PKG = "com.google.android.apps.weather"
const val GOOGLE_APP_PKG = "com.google.android.googlequicksearchbox"
