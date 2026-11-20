package app.helpmestocks.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset

/**
 * Market-hours awareness, derived from the session bounds each quote carries.
 *
 * Prices only move while a market is open, so outside sessions the widget already shows the
 * final numbers and refreshing would only spend battery and data. Nights, weekends and the
 * hours between sessions therefore cost no network at all.
 */
object Market {
    /** Providers publish closing prices with some delay (up to ~20 min on delayed feeds). */
    private const val SETTLE_SEC = 20 * 60L
    /**
     * Start checking for the next session this long before it's expected to open. Over an hour,
     * so a daylight-saving change (the session moving an hour earlier in UTC) is still caught.
     */
    private const val LEAD_SEC = 70 * 60L
    /** Keep looking this long after an expected open before assuming a holiday. */
    private const val GRACE_SEC = 6 * 3600L
    private const val DAY = 86_400L

    enum class State { OPEN, SETTLING, CLOSED }

    fun state(q: Quote, nowSec: Long): State = when {
        nowSec in q.sessionStart until q.sessionEnd -> State.OPEN
        nowSec >= q.sessionEnd && q.fetchedAt / 1000 < q.sessionEnd + SETTLE_SEC -> State.SETTLING
        else -> State.CLOSED
    }

    /** True while the session the quote belongs to is trading right now. */
    fun isOpen(q: Quote, nowSec: Long) = nowSec in q.sessionStart until q.sessionEnd

    /**
     * The next time a new session for this symbol is expected: the same clock time as the last
     * session's open on a following weekday. Weekday is judged at the session's midpoint in UTC,
     * which falls on the exchange's local date for every major market (New York, London, Tokyo,
     * Sydney). 24-hour markets (crypto) are always open, so this is never reached for them.
     */
    fun nextExpectedOpen(q: Quote): Long {
        val len = (q.sessionEnd - q.sessionStart).coerceIn(0, DAY)
        // Start from the day of the last fetch: a session more than a week old (a halted or delisted
        // symbol, a long holiday) otherwise returned a time in the past, so it fetched around the clock.
        val first = ((q.fetchedAt / 1000 - q.sessionStart) / DAY).coerceAtLeast(1)
        var k = first
        while (k < first + 8) {
            val open = q.sessionStart + k * DAY
            val mid = Instant.ofEpochSecond(open + len / 2).atOffset(ZoneOffset.UTC).dayOfWeek
            if (open + GRACE_SEC > q.fetchedAt / 1000 && mid != DayOfWeek.SATURDAY && mid != DayOfWeek.SUNDAY) return open
            k++
        }
        return q.sessionStart + k * DAY
    }

    /**
     * Whether this quote is worth fetching now.
     * - open or settling: when older than [maxAgeMs];
     * - closed with final numbers: only from shortly before the next expected open, then at the
     *   normal cadence until the new session shows up (or [GRACE_SEC] passes, e.g. a holiday).
     */
    fun needsFetch(q: Quote?, nowMs: Long, maxAgeMs: Long): Boolean {
        if (q == null) return true
        val nowSec = nowMs / 1000
        val stale = nowMs - q.fetchedAt >= maxAgeMs
        // 24-hour markets (crypto) never close: refresh by age only.
        if (q.sessionEnd - q.sessionStart >= 20 * 3600) return stale
        return when (state(q, nowSec)) {
            State.OPEN, State.SETTLING -> stale
            State.CLOSED -> stale && nowSec >= nextExpectedOpen(q) - LEAD_SEC
        }
    }
}
