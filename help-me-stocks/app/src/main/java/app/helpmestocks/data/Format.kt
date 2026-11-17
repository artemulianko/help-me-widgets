package app.helpmestocks.data

import android.content.Context
import android.text.format.DateFormat
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Number formatting in the phone's locale, with the typographic minus sign. */
class Fmt(ctx: Context?, private val locale: Locale = Locale.getDefault()) {
    private val is24h = ctx?.let { DateFormat.is24HourFormat(it) } ?: false
    private val clockFmt = DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm a", locale)

    private fun nf(decimals: Int): NumberFormat = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = decimals; maximumFractionDigits = decimals; isGroupingUsed = true
    }

    /** Sub-dollar prices (penny stocks, many crypto pairs) need more precision to move at all. */
    private fun decimals(v: Double) = when {
        abs(v) >= 1.0 || v == 0.0 -> 2
        abs(v) >= 0.01 -> 4
        abs(v) >= 0.0001 -> 6
        else -> 8
    }

    fun price(v: Double): String = nf(decimals(v)).format(v).replace('-', MINUS)

    /**
     * Price with its currency in the locale's style ("€166.54", "166,54 €", "$60.18").
     * Minor units Yahoo uses for some exchanges are shown as such: GBp → "116.60p", ZAc → "c".
     */
    fun price(v: Double, currency: String?): String {
        if (currency.isNullOrBlank()) return price(v)
        minorUnits[currency]?.let { return price(v) + it }
        val cur = runCatching { java.util.Currency.getInstance(currency.uppercase()) }.getOrNull() ?: return "${price(v)} $currency"
        val d = decimals(v)
        return NumberFormat.getCurrencyInstance(locale).apply {
            this.currency = cur; minimumFractionDigits = d; maximumFractionDigits = d
        }.format(v).replace('-', MINUS)
    }

    fun change(v: Double, ref: Double = v): String = signed(nf(max(decimals(ref), decimals(v))).format(abs(v)), v)

    fun percent(v: Double): String = signed(nf(2).format(abs(v)) + "%", v)

    fun change(q: Quote, mode: ChangeMode): String =
        if (mode == ChangeMode.PERCENT) percent(q.changePct) else change(q.change, q.price)

    /** No sign when the shown digits are all zero ("0.00%"), whatever the raw value. */
    private fun signed(body: String, v: Double) = when {
        body.none { Character.isDigit(it) && Character.getNumericValue(it) in 1..9 } -> body
        v > 0 -> "+$body"
        else -> "$MINUS$body"
    }

    fun clock(epochMs: Long): String = clockFmt.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    companion object {
        const val MINUS = '−'
        private val minorUnits = mapOf("GBp" to "p", "GBX" to "p", "ZAc" to "c", "ILA" to " ag")
        fun isFlat(v: Double) = abs(v) < 0.005
        fun direction(q: Quote) = when {
            isFlat(q.changePct) && isFlat(q.change) -> 0
            q.change > 0 -> 1
            else -> -1
        }
    }
}
