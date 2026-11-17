package app.helpmeweather.data

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Small on-device event log shown in the app's Logs screen.
 *
 * - Plain text file in app-private storage; one line per event: `epochMs|L|tag|message`.
 * - Keeps the last 3 days (pruned on read and at most hourly on write) and caps the file size.
 * - Never stores secrets: anything that looks like an API key or `key=` parameter is redacted.
 * - Mirrored to logcat in debug builds only (release logcat never sees place names).
 */
object AppLog {
    enum class Level { I, W, E }
    data class Entry(val time: Long, val level: Level, val tag: String, val message: String)

    private const val FILE = "events.log"
    private const val RETENTION_MS = 3L * 24 * 60 * 60 * 1000
    private const val MAX_BYTES = 512 * 1024L
    private const val PRUNE_EVERY_MS = 60 * 60 * 1000L
    private val lock = Any()
    @Volatile private var lastPrune = 0L

    private val secrets = listOf(
        Regex("AIza[0-9A-Za-z_\\-]{20,}"),
        Regex("(?i)(key=)[^&\\s]+"),
    )

    fun i(ctx: Context, tag: String, msg: String) = write(ctx, Level.I, tag, msg, null)
    fun w(ctx: Context, tag: String, msg: String, t: Throwable? = null) = write(ctx, Level.W, tag, msg, t)
    fun e(ctx: Context, tag: String, msg: String, t: Throwable? = null) = write(ctx, Level.E, tag, msg, t)

    fun redact(s: String): String = secrets.fold(s) { acc, r ->
        r.replace(acc) { m -> if (m.groupValues.size > 1 && m.groupValues[1].isNotEmpty()) m.groupValues[1] + "•••" else "•••" }
    }

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)

    private fun write(ctx: Context, level: Level, tag: String, msg: String, t: Throwable?) {
        val text = redact(if (t != null) "$msg: ${t.javaClass.simpleName}${t.message?.let { " – $it" } ?: ""}" else msg)
            .replace('\n', ' ').take(500)
        if (app.helpmeweather.BuildConfig.DEBUG) when (level) {
            Level.I -> Log.i("HelpMeWeather", "[$tag] $text")
            Level.W -> Log.w("HelpMeWeather", "[$tag] $text")
            Level.E -> Log.e("HelpMeWeather", "[$tag] $text")
        }
        runCatching {
            synchronized(lock) {
                val f = file(ctx)
                f.appendText("${System.currentTimeMillis()}|${level.name}|$tag|$text\n")
                val now = System.currentTimeMillis()
                if (now - lastPrune > PRUNE_EVERY_MS || f.length() > MAX_BYTES) {
                    lastPrune = now
                    pruneLocked(f, now)
                }
            }
        }
    }

    private fun pruneLocked(f: File, now: Long) {
        if (!f.exists()) return
        val keep = f.readLines().filter { line -> line.substringBefore('|').toLongOrNull()?.let { now - it < RETENTION_MS } == true }
        val trimmed = if (keep.sumOf { it.length + 1 } > MAX_BYTES) keep.takeLast(keep.size / 2) else keep
        f.writeText(if (trimmed.isEmpty()) "" else trimmed.joinToString("\n", postfix = "\n"))
    }

    /** Newest first, last 3 days. */
    fun read(ctx: Context): List<Entry> = runCatching {
        synchronized(lock) {
            val f = file(ctx)
            pruneLocked(f, System.currentTimeMillis())
            if (!f.exists()) return emptyList()
            f.readLines().mapNotNull { line ->
                val p = line.split('|', limit = 4)
                if (p.size < 4) return@mapNotNull null
                Entry(p[0].toLongOrNull() ?: return@mapNotNull null, runCatching { Level.valueOf(p[1]) }.getOrDefault(Level.I), p[2], p[3])
            }.asReversed()
        }
    }.getOrDefault(emptyList())

    fun clear(ctx: Context) {
        synchronized(lock) { runCatching { file(ctx).writeText("") } }
    }
}
