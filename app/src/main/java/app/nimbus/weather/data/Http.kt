package app.nimbus.weather.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** Tiny dependency-free JSON GET. Keeps the APK small and avoids a second HTTP stack. */
object Http {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap()): JsonElement =
        withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Encoding", "gzip")
                setRequestProperty("User-Agent", "HelpMeWeather/0.1 (Android widget)")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            try {
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val raw = stream?.let {
                    if ("gzip".equals(conn.contentEncoding, true)) GZIPInputStream(it) else it
                }?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw HttpException(code, raw.take(300))
                json.parseToJsonElement(raw)
            } finally {
                conn.disconnect()
            }
        }
}

class HttpException(val code: Int, body: String) : IOException("HTTP $code: $body")
