package app.helpmecalendar.widget

import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProvider
import androidx.glance.unit.ColorProvider
import app.helpmecalendar.data.BgTone
import app.helpmecalendar.data.Palette
import app.helpmecalendar.data.ThemeMode
import app.helpmecalendar.data.WidgetConfig

class WColors(
    val bg: ColorProvider,
    val text: ColorProvider,
    val textVariant: ColorProvider,
    val accent: ColorProvider,
    val track: ColorProvider,
)

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

@android.annotation.SuppressLint("RestrictedApi")
fun widgetColors(cfg: WidgetConfig): WColors {
    val theme = cfg.themeMode.ordinal               // 0 auto, 1 light, 2 dark
    val tone = cfg.bgTone.ordinal                   // 0 surface, 1 primary, 2 secondary
    val opacity = cfg.opacity.coerceIn(0, 100)
    if (cfg.palette == Palette.DYNAMIC) {
        // Resource-backed: follows wallpaper + dark mode live, even with transparency.
        return WColors(
            bg = ColorProvider(DynRes.bg(tone, theme, opacity)),
            text = ColorProvider(DynRes.text[tone][theme]),
            textVariant = ColorProvider(DynRes.textVariant[tone][theme]),
            accent = ColorProvider(DynRes.primary[theme]),
            track = ColorProvider(DynRes.track[theme]),
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
    return when (cfg.bgTone) {
        BgTone.SURFACE -> WColors(pick({ it.surface }, a), pick({ it.onSurface }), pick({ it.onSurfaceVar }), pick({ it.primary }), pick({ it.outlineVar }))
        BgTone.PRIMARY -> WColors(pick({ it.primaryC }, a), pick({ it.onPrimaryC }), pick({ it.onPrimaryC }, 0.78f), pick({ it.primary }), pick({ it.outlineVar }))
        BgTone.SECONDARY -> WColors(pick({ it.secondaryC }, a), pick({ it.onSecondaryC }), pick({ it.onSecondaryC }, 0.78f), pick({ it.primary }), pick({ it.outlineVar }))
    }
}
