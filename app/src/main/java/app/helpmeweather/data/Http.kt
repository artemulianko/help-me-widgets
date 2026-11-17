package app.helpmeweather.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** Tiny dependency-free JSON GET. Keeps the APK small and avoids a second HTTP stack. */
object Http {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

    private const val MAX_BODY = 2L * 1024 * 1024   // decompressed; real forecasts are ~10-60 KB
    private const val MAX_ERROR_BODY = 16L * 1024
    private const val DEADLINE_MS = 30_000L

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
                // Hard total deadline: disconnecting unblocks any read stuck on a slow server.
                withTimeout(DEADLINE_MS) {
                    runInterruptible {
                        val code = conn.responseCode
                        val ok = code in 200..299
                        val stream = (if (ok) conn.inputStream else conn.errorStream)?.let {
                            if ("gzip".equals(conn.contentEncoding, true)) GZIPInputStream(it) else it
                        }
                        val raw = stream?.use { readBounded(it, if (ok) MAX_BODY else MAX_ERROR_BODY, failOnOverflow = ok) }.orEmpty()
                        if (!ok) throw HttpException(code, errorMessage(raw))
                        json.parseToJsonElement(raw)
                    }
                }
            } finally {
                conn.disconnect()
            }
        }

    /** Pulls the human-readable message out of JSON error bodies (Google: error.message). */
    private fun errorMessage(raw: String): String = runCatching {
        val e = json.parseToJsonElement(raw)
        (e["error"]["message"].s ?: e["reason"].s ?: e["error"].s)
    }.getOrNull()?.trim()?.take(200) ?: raw.replace(Regex("\\s+"), " ").take(200)

    /** Reads at most [limit] bytes *after* decompression, so gzip bombs can't exhaust memory. */
    internal fun readBounded(input: InputStream, limit: Long, failOnOverflow: Boolean): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) {
                if (failOnOverflow) throw IOException("Response larger than ${limit / 1024} KB")
                out.write(buf, 0, (n - (total - limit)).toInt()); break
            }
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }
}

class HttpException(val code: Int, val detail: String) : IOException("HTTP $code: $detail")
