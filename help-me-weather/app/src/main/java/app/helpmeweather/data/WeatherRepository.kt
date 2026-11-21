package app.helpmeweather.data

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

import android.content.Context
import android.util.Log
import app.helpmeweather.widget.WidgetRenderer

object WeatherRepository {
    private const val TAG = "HelpMeWeather"

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

    private fun isAppVisible(): Boolean = runCatching {
        val info = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(info)
        info.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }.getOrDefault(false)

    private fun providerName(s: WeatherSource) = if (s is GoogleWeatherSource) "Google" else "Open-Meteo"

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
    /** Widget-tap, worker and in-app refreshes run one at a time, never interleaved. */
    private val lock = Mutex()

    /**
     * [widgets] = false: fetch only, no spinner and no widget redraw (the caller redraws later).
     * Used by the refresh window: while any window is in front of the home screen, the launcher
     * holds widget updates back and applies them afterwards, and on a Pixel 11 Pro one of those
     * late updates sometimes left the widget an empty frame until the next update.
     */
    suspend fun refresh(ctx: Context, force: Boolean, foreground: Boolean = false, reason: String = "scheduled", widgets: Boolean = true, spinner: Boolean = force): Result<Boolean> =
        lock.withLock { refreshLocked(ctx, force, foreground, reason, widgets, spinner) }

    private suspend fun refreshLocked(ctx: Context, force: Boolean, foreground: Boolean, reason: String, widgets: Boolean, spinner: Boolean): Result<Boolean> {
        init(ctx)
        val started = System.currentTimeMillis()
        val store = Store(ctx)
        // Spinner only for user-initiated refreshes; background refreshes cost a single render.
        var spinnerMs = 0L
        if (spinner && widgets) {
            store.setRefreshing(true)
            spinnerMs = timed { WidgetRenderer.renderAll(ctx) }
        }
        var changed = false
        val prefs = ctx.getSharedPreferences("meta", Context.MODE_PRIVATE)
        val currentHour = System.currentTimeMillis() / 3_600_000L
        val hourRolled = prefs.getLong("rendered_hour", -1L) != currentHour
        try {
            var app = store.appNow()
            val configs = widgetIds(ctx).map { store.widgetNow(it) }
            AppLog.i(ctx, "Refresh", "Started ($reason${if (force) ", forced" else ""}) · ${configs.size} widget(s)${if (spinner && widgets) " · spinner shown in $spinnerMs ms" else ""}")
            val needsDevice = configs.isEmpty() || configs.any { it.location == null }

            // Location: only when something uses it, and only when the OS lets us (bg permission
            // or the app is in the foreground). Reuses the fused cached fix to avoid GPS.
            val locStale = System.currentTimeMillis() - app.deviceLocationAt > 20 * 60_000L
            // "While in use" location also works for background jobs while one of our screens is
            // visible (e.g. a refresh triggered by adding a widget from the app).
            val visible = foreground || isAppVisible()
            if (needsDevice && (force || locStale || app.deviceLocation == null) &&
                (visible || Locations.hasBackground(ctx))
            ) {
                // Known location → wait at most a few seconds for a better fix, so a manual refresh stays snappy.
                val timeout = if (app.deviceLocation != null) 5_000L else 25_000L
                val p = runCatching { Locations.devicePlace(ctx, timeout, precise = visible) }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()
                if (p != null) {
                    store.updateApp { it.copy(deviceLocation = p, deviceLocationAt = System.currentTimeMillis()) }
                    app = store.appNow()
                    AppLog.i(ctx, "Location", "Updated to ${p.name}")
                } else {
                    AppLog.w(ctx, "Location", "No fix available; using ${app.deviceLocation?.name ?: "nothing yet"}")
                }
            } else if (needsDevice && app.deviceLocation != null && locStale && !visible && !Locations.hasBackground(ctx)) {
                AppLog.i(ctx, "Location", "Kept ${app.deviceLocation?.name} (no background location permission)")
            }
            if (needsDevice && app.deviceLocation == null && configs.all { it.location == null }) {
                AppLog.w(ctx, "Location", "No location yet: open the app to allow location or pick a city")
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
                if (fresh && !force && cached.place.name == place.name) {
                    AppLog.i(ctx, "Fetch", "${place.name}: cache still fresh, skipped")
                    continue
                }
                // Protect API quota: even a manual refresh reuses a forecast fetched <1 min ago.
                if (force && cached != null && cached.place.name == place.name && System.currentTimeMillis() - cached.fetchedAt < 60_000L) {
                    AppLog.i(ctx, "Fetch", "${place.name}: fetched under a minute ago, reusing it (saves API quota)")
                    continue
                }
                val t0 = System.currentTimeMillis()
                AppLog.i(ctx, "Fetch", "Requesting ${providerName(primary)} for ${place.name}")
                val data = runCatching { primary.fetch(place) }.recoverCatching { e ->
                    // Cancelled (replaced or stopped): not a provider failure, so no fallback request.
                    if (fallback == null || e is kotlinx.coroutines.CancellationException) throw e
                    AppLog.w(ctx, "Fetch", "${place.name}: ${providerName(primary)} failed after ${System.currentTimeMillis() - t0} ms, trying Open-Meteo", e)
                    error = "${providerName(primary)}: ${(e as? HttpException)?.detail ?: e.message ?: e.javaClass.simpleName} Showing Open-Meteo instead."
                    fallback.fetch(place)
                }.onFailure { e ->
                    currentCoroutineContext().ensureActive() // cancelled (replaced/stopped): not a failure
                    AppLog.e(ctx, "Fetch", "${place.name}: no data (${providerName(primary)}${if (fallback != null) " and Open-Meteo" else ""} failed); keeping last forecast", e)
                    error = "Couldn't update: ${(e as? HttpException)?.detail ?: e.message ?: e.javaClass.simpleName}. Showing the last forecast."
                }.getOrNull() ?: continue
                store.putWeather(data.copy(place = place))
                changed = true
                AppLog.i(
                    ctx, "Fetch",
                    "${place.name}: ${if (data.source == Provider.GOOGLE) "Google" else "Open-Meteo"} · " +
                        "${"%.1f".format(data.current.tempC)}°C ${data.current.condition.name.lowercase().replace('_', ' ')} · ${System.currentTimeMillis() - t0} ms",
                )
            }
            store.pruneWeather(places.map { it.key }.toSet() + listOfNotNull(app.deviceLocation?.key))
            store.setError(error)
            AppLog.i(ctx, "Refresh", "Done in ${System.currentTimeMillis() - started} ms${if (error != null) " (with errors)" else ""}")
            // A failure when nothing could be fetched, so the worker's backoff and the refresh
            // button's retry kick in (it used to report success whenever a place was configured).
            val willRedraw = widgets && (force || changed || (hourRolled && places.isNotEmpty()))
            return if (error == null || changed) Result.success(willRedraw) else Result.failure(Exception(error))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // the worker logs why Android stopped it
        } catch (e: Exception) {
            AppLog.e(ctx, "Refresh", "Failed", e)
            store.setError(e.message)
            return Result.failure(e)
        } finally {
            // Must run even when cancelled (a widget tap's time budget ran out, or the job was
            // stopped), or the spinner would outlive the refresh. Also redraw when the clock hour
            // rolled over since the last render so hourly slots never show past hours.
            withContext(NonCancellable) {
                if (spinner && widgets) store.setRefreshing(false)
                if (widgets && (force || changed || hourRolled)) {
                    val ms = timed { WidgetRenderer.renderAll(ctx) }
                    prefs.edit().putLong("rendered_hour", System.currentTimeMillis() / 3_600_000L).apply()
                    if (force) AppLog.i(ctx, "Refresh", "Widgets redrawn in $ms ms")
                }
            }
        }
    }

    private inline fun timed(block: () -> Unit): Long {
        val t = android.os.SystemClock.elapsedRealtime(); block(); return android.os.SystemClock.elapsedRealtime() - t
    }
}
