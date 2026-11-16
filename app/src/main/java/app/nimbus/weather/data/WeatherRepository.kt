package app.nimbus.weather.data

import android.content.Context
import android.util.Log
import app.nimbus.weather.widget.WidgetRenderer

object WeatherRepository {
    private const val TAG = "NimbusRepo"

    /** Package + signing-cert SHA-1 headers for Google API key app restrictions. */
    @Volatile private var appHeaders: Map<String, String>? = null

    fun init(ctx: Context) {
        if (appHeaders != null) return
        appHeaders = runCatching {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
            val cert = info.signingInfo!!.apkContentsSigners.first().toByteArray()
            val sha1 = java.security.MessageDigest.getInstance("SHA-1").digest(cert).joinToString("") { "%02X".format(it) }
            mapOf("X-Android-Package" to ctx.packageName, "X-Android-Cert" to sha1)
        }.getOrDefault(emptyMap())
    }

    fun sourceFor(app: AppSettings): Pair<WeatherSource, WeatherSource?> {
        val key = app.googleApiKey
        return if (app.provider == ProviderPref.GOOGLE && key.isNotBlank()) {
            GoogleWeatherSource(key, appHeaders.orEmpty()) to OpenMeteoSource()
        } else {
            OpenMeteoSource() to null
        }
    }

    fun widgetIds(ctx: Context): List<Int> = WidgetRenderer.ids(ctx).toList()

    /**
     * Fetch forecasts for every place a widget (or the app) shows. Each place is only fetched
     * when its cache is older than ~75% of the refresh interval, unless [force] is set, so
     * overlapping triggers never cost extra network or battery.
     */
    suspend fun refresh(ctx: Context, force: Boolean, foreground: Boolean = false): Result<Unit> {
        init(ctx)
        val store = Store(ctx)
        // Spinner only for user-initiated refreshes; background refreshes cost a single render.
        if (force) {
            store.setRefreshing(true)
            WidgetRenderer.renderAll(ctx)
        }
        var changed = false
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
                // Known location → wait at most a few seconds for a better fix, so a manual refresh stays snappy.
                val timeout = if (app.deviceLocation != null) 5_000L else 25_000L
                runCatching { Locations.devicePlace(ctx, timeout) }.getOrNull()?.let { p ->
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
                changed = true
            }
            store.pruneWeather(places.map { it.key }.toSet() + listOfNotNull(app.deviceLocation?.key))
            store.setError(error)
            return if (error == null || places.isNotEmpty()) Result.success(Unit) else Result.failure(Exception(error))
        } catch (e: Exception) {
            Log.e(TAG, "refresh failed", e)
            store.setError(e.message)
            return Result.failure(e)
        } finally {
            if (force) store.setRefreshing(false)
            // Running Glance sessions already recompose from the DataStore flows; this
            // re-renders widgets whose session has ended. Skipped when nothing changed.
            if (force || changed) WidgetRenderer.renderAll(ctx)
        }
    }
}
