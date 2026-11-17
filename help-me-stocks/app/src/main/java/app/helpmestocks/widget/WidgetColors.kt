package app.helpmestocks.widget

import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProvider
import androidx.glance.unit.ColorProvider
import app.helpmestocks.data.BgTone
import app.helpmestocks.data.Palette
import app.helpmestocks.data.ThemeMode
import app.helpmestocks.data.UpColor
import app.helpmestocks.data.WidgetConfig

class WColors(
    val bg: ColorProvider,
    val text: ColorProvider,
    val textVariant: ColorProvider,
    val accent: ColorProvider,
    val track: ColorProvider,
    val change: ChangeColors,
)

/** Gain/loss colours: line + text colour, and container/on-container for the change chip. */
class ChangeColors(
    val up: ColorProvider, val down: ColorProvider, val flat: ColorProvider,
    val upContainer: ColorProvider, val onUpContainer: ColorProvider,
    val downContainer: ColorProvider, val onDownContainer: ColorProvider,
    val flatContainer: ColorProvider, val onFlatContainer: ColorProvider,
    val upSolid: ColorProvider, val downSolid: ColorProvider, val flatSolid: ColorProvider, val onSolid: ColorProvider,
) {
    fun line(dir: Int) = when { dir > 0 -> up; dir < 0 -> down; else -> flat }
    fun container(dir: Int, solid: Boolean) = when {
        solid -> when { dir > 0 -> upSolid; dir < 0 -> downSolid; else -> flatSolid }
        else -> when { dir > 0 -> upContainer; dir < 0 -> downContainer; else -> flatContainer }
    }
    fun onContainer(dir: Int, solid: Boolean) = when {
        solid -> onSolid
        else -> when { dir > 0 -> onUpContainer; dir < 0 -> onDownContainer; else -> onFlatContainer }
    }
}

/**
 * Google's green/red tones (as in Google Finance), tuned per theme for contrast on light and
 * dark containers. RED_UP swaps them for the East Asian convention.
 */
private object Gain {
    val green = Color(0xFF188038) to Color(0xFF6DD58C)
    val red = Color(0xFFD93025) to Color(0xFFF28B82)
    val greenC = Color(0xFFC4EED0) to Color(0xFF0F5223)
    val onGreenC = Color(0xFF0D652D) to Color(0xFFC4EED0)
    val redC = Color(0xFFFAD2CF) to Color(0xFF8C1D18)
    val onRedC = Color(0xFFA50E0E) to Color(0xFFFAD2CF)
    val greenSolid = Color(0xFF1E8E3E) to Color(0xFF1E8E3E)
    val redSolid = Color(0xFFD93025) to Color(0xFFD93025)
}

private fun changeColors(cfg: WidgetConfig, flat: ColorProvider, flatC: ColorProvider, onFlatC: ColorProvider): ChangeColors {
    fun p(c: Pair<Color, Color>): ColorProvider = when (cfg.themeMode) {
        ThemeMode.SYSTEM -> ColorProvider(day = c.first, night = c.second)
        ThemeMode.LIGHT -> ColorProvider(c.first)
        ThemeMode.DARK -> ColorProvider(c.second)
    }
    val g = cfg.upColor == UpColor.GREEN_UP
    return ChangeColors(
        up = p(if (g) Gain.green else Gain.red), down = p(if (g) Gain.red else Gain.green), flat = flat,
        upContainer = p(if (g) Gain.greenC else Gain.redC), onUpContainer = p(if (g) Gain.onGreenC else Gain.onRedC),
        downContainer = p(if (g) Gain.redC else Gain.greenC), onDownContainer = p(if (g) Gain.onRedC else Gain.onGreenC),
        flatContainer = flatC, onFlatContainer = onFlatC,
        upSolid = p(if (g) Gain.greenSolid else Gain.redSolid), downSolid = p(if (g) Gain.redSolid else Gain.greenSolid),
        flatSolid = flatC, onSolid = ColorProvider(Color.White),
    )
}

/** A static (non-wallpaper) scheme for one brightness. */
data class StaticScheme(
    val surface: Color, val primaryC: Color, val secondaryC: Color,
    val onSurface: Color, val onSurfaceVar: Color, val onPrimaryC: Color, val onSecondaryC: Color,
    val primary: Color, val outlineVar: Color,
)

object Palettes {
    /** Seed hue + chroma for each preset palette. */
    private fun seed(p: Palette): Pair<Float, Float> = when (p) {
        Palette.BLUE -> 217f to 0.80f
        Palette.TEAL -> 180f to 0.60f
        Palette.GREEN -> 135f to 0.45f
        Palette.AMBER -> 38f to 0.85f
        Palette.ROSE -> 345f to 0.65f
        Palette.VIOLET -> 268f to 0.55f
        Palette.MONO, Palette.DYNAMIC -> 220f to 0.0f
    }

    fun scheme(p: Palette, dark: Boolean): StaticScheme {
        val (h, s) = seed(p)
        fun c(sat: Float, l: Float) = Color.hsl(h, (sat).coerceIn(0f, 1f), l)
        return if (!dark) StaticScheme(
            surface = c(s * 0.30f, 0.95f), primaryC = c(s, 0.89f), secondaryC = c(s * 0.40f, 0.90f),
            onSurface = c(s * 0.15f, 0.11f), onSurfaceVar = c(s * 0.12f, 0.33f),
            onPrimaryC = c(s, 0.13f), onSecondaryC = c(s * 0.40f, 0.14f),
            primary = c(s, 0.42f), outlineVar = c(s * 0.15f, 0.80f),
        ) else StaticScheme(
            surface = c(s * 0.18f, 0.12f), primaryC = c(s * 0.55f, 0.27f), secondaryC = c(s * 0.22f, 0.24f),
            onSurface = c(s * 0.10f, 0.91f), onSurfaceVar = c(s * 0.12f, 0.78f),
            onPrimaryC = c(s * 0.70f, 0.90f), onSecondaryC = c(s * 0.30f, 0.90f),
            primary = c(s, 0.78f), outlineVar = c(s * 0.12f, 0.30f),
        )
    }
}

/**
 * Wallpaper (Material You) colours are passed to Glance as colour *resources* so they keep
 * following wallpaper and dark-mode changes without a re-render. Glance marks the
 * resource-backed ColorProvider as library-internal, but it is the documented way RemoteViews
 * consume dynamic colours and the same code Help Me Weather ships.
 */
@android.annotation.SuppressLint("RestrictedApi")
fun widgetColors(cfg: WidgetConfig): WColors {
    val theme = cfg.themeMode.ordinal               // 0 auto, 1 light, 2 dark
    val tone = cfg.bgTone.ordinal                   // 0 surface, 1 primary, 2 secondary
    val opacity = cfg.opacity.coerceIn(0, 100)
    if (cfg.palette == Palette.DYNAMIC) {
        // Resource-backed: follows wallpaper + dark mode live, even with transparency.
        val track = ColorProvider(DynRes.track[theme])
        val variant = ColorProvider(DynRes.textVariant[tone][theme])
        return WColors(
            bg = ColorProvider(DynRes.bg(tone, theme, opacity)),
            text = ColorProvider(DynRes.text[tone][theme]),
            textVariant = variant,
            accent = ColorProvider(DynRes.primary[theme]),
            track = track,
            change = changeColors(cfg, variant, track, ColorProvider(DynRes.text[tone][theme])),
        )
    }
    val light = Palettes.scheme(cfg.palette, false)
    val dark = Palettes.scheme(cfg.palette, true)
    fun pick(f: (StaticScheme) -> Color, alpha: Float = 1f): ColorProvider = when (cfg.themeMode) {
        ThemeMode.SYSTEM -> ColorProvider(day = f(light).copy(alpha = alpha), night = f(dark).copy(alpha = alpha))
        ThemeMode.LIGHT -> ColorProvider(f(light).copy(alpha = alpha))
        ThemeMode.DARK -> ColorProvider(f(dark).copy(alpha = alpha))
    }
    val a = opacity / 100f
    val track = pick({ it.outlineVar })
    fun ch(variant: ColorProvider, text: ColorProvider) = changeColors(cfg, variant, track, text)
    return when (cfg.bgTone) {
        BgTone.SURFACE -> pick({ it.onSurfaceVar }).let { v -> pick({ it.onSurface }).let { t -> WColors(pick({ it.surface }, a), t, v, pick({ it.primary }), track, ch(v, t)) } }
        BgTone.PRIMARY -> pick({ it.onPrimaryC }, 0.78f).let { v -> pick({ it.onPrimaryC }).let { t -> WColors(pick({ it.primaryC }, a), t, v, pick({ it.primary }), track, ch(v, t)) } }
        BgTone.SECONDARY -> pick({ it.onSecondaryC }, 0.78f).let { v -> pick({ it.onSecondaryC }).let { t -> WColors(pick({ it.secondaryC }, a), t, v, pick({ it.primary }), track, ch(v, t)) } }
    }
}
