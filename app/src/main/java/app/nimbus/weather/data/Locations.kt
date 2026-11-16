package app.nimbus.weather.data

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

    /**
     * Power-friendly device location: reuse the fused provider's cached fix when it is fresh,
     * otherwise ask for a single balanced-power (cell/Wi-Fi, no GPS) fix. Never keeps a listener.
     */
    @SuppressLint("MissingPermission")
    suspend fun device(ctx: Context, maxAgeMs: Long = 30 * 60_000L): Location? {
        if (!hasForeground(ctx)) return null
        val client = LocationServices.getFusedLocationProviderClient(ctx)
        val last = runCatching { client.lastLocation.await() }.getOrNull()
        if (last != null && System.currentTimeMillis() - last.time < maxAgeMs) return last
        val req = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setMaxUpdateAgeMillis(maxAgeMs)
            .setDurationMillis(20_000)
            .build()
        return withTimeoutOrNull(25_000) { runCatching { client.getCurrentLocation(req, null).await() }.getOrNull() } ?: last
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
    suspend fun devicePlace(ctx: Context): Place? {
        val loc = device(ctx) ?: return null
        val (name, region) = nameFor(ctx, loc.latitude, loc.longitude)
        return Place(name, loc.latitude, loc.longitude, region)
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
