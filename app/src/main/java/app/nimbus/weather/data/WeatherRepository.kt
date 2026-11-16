package app.nimbus.weather.data

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import app.nimbus.weather.BuildConfig
import app.nimbus.weather.widget.WeatherWidget

object WeatherRepository {
    private const val TAG = "NimbusRepo"

    fun sourceFor(app: AppSettings): Pair<WeatherSource, WeatherSource?> {
        val key = app.googleApiKey.ifBlank { BuildConfig.GOOGLE_WEATHER_KEY }
        return if (app.provider == ProviderPref.GOOGLE && key.isNotBlank()) {
            GoogleWeatherSource(key) to OpenMeteoSource()
        } else {
            OpenMeteoSource() to null
        }
    }

    suspend fun widgetIds(ctx: Context): List<Int> = runCatching {
        val m = GlanceAppWidgetManager(ctx)
        m.getGlanceIds(WeatherWidget::class.java).map { m.getAppWidgetId(it) }
    }.getOrDefault(emptyList())

    /**
     * Fetch forecasts for every place a widget (or the app) shows. Each place is only fetched
     * when its cache is older than ~75% of the refresh interval, unless [force] is set, so
     * overlapping triggers never cost extra network or battery.
     */
    suspend fun refresh(ctx: Context, force: Boolean, foreground: Boolean = false): Result<Unit> {
        val store = Store(ctx)
        store.setRefreshing(true)
        WeatherWidget().updateAll(ctx)
        try {
            var app = store.appNow()
            val configs = widgetIds(ctx).map { store.widgetNow(it) }
            val needsDevice = configs.isEmpty() || configs.any { it.location == null }

            // Location: only when something uses it, and only when the OS lets us (bg permission
            // or the app is in the foreground). Reuses the fused cached fix to avoid GPS.
            val locStale = System.currentTimeMillis() - app.deviceLocationAt > 20 * 60_000L
            if (needsDevice && (force || locStale || app.deviceLocation == null) &&
                (foreground || Locations.hasBackground(ctx))
            ) {
                runCatching { Locations.devicePlace(ctx) }.getOrNull()?.let { p ->
                    store.updateApp { it.copy(deviceLocation = p, deviceLocationAt = System.currentTimeMillis()) }
                    app = store.appNow()
                }
            }

            val places = buildList {
                configs.forEach { c -> (c.location ?: app.deviceLocation)?.let(::add) }
                if (configs.isEmpty()) app.deviceLocation?.let(::add)
            }.distinctBy { it.key }

            val maxAge = (app.refreshMinutes.coerceAtLeast(15) * 60_000L * 3 / 4).coerceAtLeast(10 * 60_000L)
            val (primary, fallback) = sourceFor(app)
            var error: String? = null
            for (place in places) {
                val cached = store.weatherNow(place)
                val fresh = cached != null && System.currentTimeMillis() - cached.fetchedAt < maxAge
                if (fresh && !force && cached.place.name == place.name) continue
                val data = runCatching { primary.fetch(place) }.recoverCatching { e ->
                    Log.w(TAG, "primary source failed for ${place.name}", e)
                    error = e.message
                    fallback?.fetch(place) ?: throw e
                }.onFailure { e ->
                    Log.w(TAG, "all sources failed for ${place.name}", e)
                    error = e.message ?: e.javaClass.simpleName
                }.getOrNull() ?: continue
                store.putWeather(data.copy(place = place))
            }
            store.pruneWeather(places.map { it.key }.toSet() + listOfNotNull(app.deviceLocation?.key))
            store.setError(error)
            return if (error == null || places.isNotEmpty()) Result.success(Unit) else Result.failure(Exception(error))
        } catch (e: Exception) {
            Log.e(TAG, "refresh failed", e)
            store.setError(e.message)
            return Result.failure(e)
        } finally {
            store.setRefreshing(false)
            WeatherWidget().updateAll(ctx)
        }
    }
}
