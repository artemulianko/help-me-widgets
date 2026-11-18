package app.helpmeweather.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.resume

object Locations {

    fun hasForeground(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackground(ctx: Context) = hasForeground(ctx) &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasPrecise(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * One location fix, never a listener.
     *
     * [precise] (the app is on screen or the refresh button was tapped, and precise permission
     * is granted): a fresh high-accuracy fix. Cell-only network fixes can be tens of km off
     * (a Pixel in central Warsaw with Wi-Fi off was placed in Płock), so a fix this old or
     * vaguer isn't trusted. Otherwise: the fused cached fix when recent, or one balanced-power
     * (cell/Wi-Fi) fix. A cached fix older than [STALE_MS] is never passed off as current.
     */
    @SuppressLint("MissingPermission")
    suspend fun device(ctx: Context, maxAgeMs: Long = 30 * 60_000L, timeoutMs: Long = 25_000, precise: Boolean = false): Location? {
        if (!hasForeground(ctx)) return null
        val fine = precise && hasPrecise(ctx)
        val maxAge = if (fine) 10 * 60_000L else maxAgeMs
        val client = LocationServices.getFusedLocationProviderClient(ctx)
        val last = runCatching { client.lastLocation.await() }.getOrNull()
        fun age(l: Location) = System.currentTimeMillis() - l.time
        if (last != null && age(last) < maxAge && (!fine || last.accuracy <= 500f)) return last.also { log(ctx, it, "cached") }
        val req = CurrentLocationRequest.Builder()
            .setPriority(if (fine) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setMaxUpdateAgeMillis(maxAge)
            .setDurationMillis(timeoutMs)
            .build()
        val fresh = withTimeoutOrNull(timeoutMs) { runCatching { client.getCurrentLocation(req, null).await() }.getOrNull() }
        if (fresh != null) return fresh.also { log(ctx, it, if (fine) "precise" else "approximate") }
        return last?.takeIf { age(it) < STALE_MS }?.also { log(ctx, it, "cached, no newer fix") }
    }

    private const val STALE_MS = 30 * 60_000L

    /** Accuracy and age only, never coordinates. */
    private fun log(ctx: Context, l: Location, kind: String) {
        val acc = if (l.accuracy >= 1000f) "%.0f km".format(Locale.US, l.accuracy / 1000f) else "%.0f m".format(Locale.US, l.accuracy)
        AppLog.i(ctx, "Location", "Fix: $kind, ±$acc, ${(System.currentTimeMillis() - l.time) / 1000} s old")
    }

    /** On-device reverse geocoding (no network quota); falls back to coordinates. */
    suspend fun nameFor(ctx: Context, lat: Double, lon: Double): Pair<String, String?> {
        if (!Geocoder.isPresent()) return fallbackName(lat, lon) to null
        val addr = withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    Geocoder(ctx, Locale.getDefault()).getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<android.location.Address>) { cont.resume(addresses.firstOrNull()) }
                        override fun onError(errorMessage: String?) { cont.resume(null) }
                    })
                }.onFailure { cont.resume(null) }
            }
        }
        val a = addr ?: return fallbackName(lat, lon) to null
        val name = a.locality ?: a.subAdminArea ?: a.adminArea ?: return fallbackName(lat, lon) to null
        return name to listOfNotNull(a.adminArea, a.countryName).distinct().joinToString(", ").ifEmpty { null }
    }

    private fun fallbackName(lat: Double, lon: Double) = "%.2f, %.2f".format(Locale.US, lat, lon)

    /** Resolve the device location to a named Place, or null if unavailable. */
    suspend fun devicePlace(ctx: Context, timeoutMs: Long = 25_000, precise: Boolean = false): Place? {
        val loc = device(ctx, timeoutMs = timeoutMs, precise = precise) ?: return null
        // Round to ~1 km before geocoding or saving: nothing finer is ever kept or shared.
        val lat = Math.round(loc.latitude * 100) / 100.0
        val lon = Math.round(loc.longitude * 100) / 100.0
        val (name, region) = nameFor(ctx, lat, lon)
        return Place(name, lat, lon, region)
    }

    /** City search via the free Open-Meteo geocoding API. */
    suspend fun search(query: String): List<Place> {
        if (query.isBlank()) return emptyList()
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=10&format=json" +
            "&language=${Locale.getDefault().language}&name=${URLEncoder.encode(query.trim(), "UTF-8")}"
        val j = Http.getJson(url)
        return j["results"].arr.mapNotNull { r ->
            Place(
                name = r["name"].s ?: return@mapNotNull null,
                lat = r["latitude"].d ?: return@mapNotNull null,
                lon = r["longitude"].d ?: return@mapNotNull null,
                region = listOfNotNull(r["admin1"].s, r["country"].s).distinct().joinToString(", ").ifEmpty { null },
            )
        }
    }
}
