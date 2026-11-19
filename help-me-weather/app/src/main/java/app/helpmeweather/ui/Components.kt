@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.helpmeweather.ui

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Typeface
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.provideContent
import app.helpmeweather.data.Palette
import app.helpmeweather.widget.Palettes
import app.helpmeweather.widget.WeatherContent
import app.helpmeweather.widget.WidgetState
import kotlinx.coroutines.delay

// ---- theme -----------------------------------------------------------------------------------

/** Google Sans Flex when the device ships it (Pixel), so the app matches system apps. */
private fun systemDisplayFamily(): FontFamily? =
    listOf("google-sans-flex", "google-sans").firstNotNullOfOrNull { name ->
        Typeface.create(name, Typeface.NORMAL).takeIf { it != Typeface.DEFAULT }?.let { FontFamily(it) }
    }

private fun Typography.withFamily(f: FontFamily?): Typography {
    if (f == null) return this
    fun TextStyle.w() = copy(fontFamily = f)
    return copy(
        displayLarge = displayLarge.w(), displayMedium = displayMedium.w(), displaySmall = displaySmall.w(),
        headlineLarge = headlineLarge.w(), headlineMedium = headlineMedium.w(), headlineSmall = headlineSmall.w(),
        titleLarge = titleLarge.w(), titleMedium = titleMedium.w(), titleSmall = titleSmall.w(),
        bodyLarge = bodyLarge.w(), bodyMedium = bodyMedium.w(), bodySmall = bodySmall.w(),
        labelLarge = labelLarge.w(), labelMedium = labelMedium.w(), labelSmall = labelSmall.w(),
    )
}

@Composable
fun HelpMeWeatherTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    val typography = remember { Typography().withFamily(systemDisplayFamily()) }
    MaterialExpressiveTheme(colorScheme = scheme, typography = typography) {
        // Expose test tags as resource ids so UiAutomator end-to-end tests can find them.
        Box(Modifier.semantics { testTagsAsResourceId = true }) { content() }
    }
}

// ---- live widget preview: renders the real Glance RemoteViews -----------------------------

private class PreviewWidget(private val state: WidgetState) : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { WeatherContent(state) }
    }
}

/** The actual widget RemoteViews, inflated in-app, on a neutral "home screen" panel. */
@Composable
fun WidgetPreview(state: WidgetState, size: DpSize, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    suspend fun androidx.compose.runtime.ProduceStateScope<RemoteViews?>.draw() {
        value = runCatching { withContext(Dispatchers.Default) { PreviewWidget(state).compose(ctx, size = size) } }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; app.helpmeweather.data.AppLog.w(ctx, "Preview", "Couldn't draw the preview", it) }
            .getOrNull()
    }
    // Checkpoints in the Logs: when the preview first appears, and slow draws (a blank or stale
    // preview on a real phone is otherwise invisible to us).
    val opened = remember { android.os.SystemClock.elapsedRealtime() }
    var firstDrawn by remember { mutableStateOf(false) }
    // Draw only while the screen is visible: a stopped activity keeps its composition, and the
    // preview used to redraw in the background on every widget data change.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val visible by remember(lifecycle) { lifecycle.currentStateFlow }.collectAsState()
    val started = visible.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
    val rv by produceState<RemoteViews?>(null, state, size, started) {
        if (!started) return@produceState
        delay(50) // coalesce rapid slider changes
        val t0 = android.os.SystemClock.elapsedRealtime()
        try { draw() } finally {
            val now = android.os.SystemClock.elapsedRealtime()
            if (value != null && !firstDrawn) { firstDrawn = true; app.helpmeweather.data.AppLog.i(ctx, "Preview", "Preview first drawn ${now - opened} ms after opening") }
            else if (now - t0 > 1_500) app.helpmeweather.data.AppLog.w(ctx, "Preview", "Slow preview draw: ${now - t0} ms")
        }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .testTag("widget_preview"),
        contentAlignment = Alignment.Center,
    ) {
        // A stand-in for your home screen, so background opacity reads exactly as it will there.
        WallpaperBackdrop(Modifier.matchParentSize())
        AndroidView(
            factory = { FrameLayout(it) },
            update = { fl ->
                fl.removeAllViews()
                rv?.let { runCatching { fl.addView(it.apply(fl.context, fl)) } }
            },
            modifier = Modifier.padding(vertical = 20.dp).size(size),
        )
        // The preview is a picture: its tap actions (refresh, open a forecast or a quote) are live
        // RemoteViews, so swallow touches instead of launching things from the editor.
        Box(Modifier.matchParentSize().pointerInput(Unit) {
            awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
        })
    }
}

/**
 * Soft wallpaper-like backdrop from the colors of *your* wallpaper (WallpaperManager exposes
 * them without any permission), falling back to the theme's accents.
 */
@Composable
private fun WallpaperBackdrop(modifier: Modifier) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val wc = remember { runCatching { WallpaperManager.getInstance(ctx).getWallpaperColors(WallpaperManager.FLAG_SYSTEM) }.getOrNull() }
    val a = wc?.primaryColor?.let { Color(it.toArgb()) } ?: cs.primary
    val b = wc?.secondaryColor?.let { Color(it.toArgb()) } ?: cs.tertiary
    val c = wc?.tertiaryColor?.let { Color(it.toArgb()) } ?: cs.secondaryContainer
    Canvas(modifier) {
        drawRect(Brush.linearGradient(listOf(a, b), start = Offset.Zero, end = Offset(size.width, size.height)))
        val r1 = size.minDimension * 0.9f
        val p1 = Offset(size.width * 0.12f, size.height * 0.15f)
        drawCircle(Brush.radialGradient(listOf(c, c.copy(alpha = 0f)), center = p1, radius = r1), radius = r1, center = p1)
        val r2 = size.minDimension * 0.7f
        val p2 = Offset(size.width * 0.9f, size.height * 0.95f)
        drawCircle(Brush.radialGradient(listOf(a.copy(alpha = 0.9f), a.copy(alpha = 0f)), center = p2, radius = r2), radius = r2, center = p2)
    }
}

/** Real Pixel launcher spans (~97dp per column, ~105dp per row). */
val previewSizes = listOf(
    "2×2" to DpSize(190.dp, 210.dp),
    "4×1" to DpSize(380.dp, 105.dp),
    "4×2" to DpSize(380.dp, 210.dp),
    "4×3" to DpSize(380.dp, 316.dp),
)

/**
 * The editor's preview sizes: this widget's real size on your home screen first (selected, so the
 * preview matches what you see after resizing it), labelled by its grid span, then the standard
 * sizes. Portrait is the narrower of the sizes the launcher reports.
 */
fun editorPreviewSizes(ctx: android.content.Context, appWidgetId: Int): List<Pair<String, DpSize>> {
    val o = runCatching { android.appwidget.AppWidgetManager.getInstance(ctx).getAppWidgetOptions(appWidgetId) }.getOrNull()
        ?: return previewSizes
    val reported = if (android.os.Build.VERSION.SDK_INT >= 31) {
        o.getParcelableArrayList(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_SIZES, android.util.SizeF::class.java)
            ?.filter { it.width > 0f && it.height > 0f }?.minByOrNull { it.width }?.let { DpSize(it.width.dp, it.height.dp) }
    } else null
    val real = reported ?: run {
        val w = o.getInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val h = o.getInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        if (w > 0 && h > 0) DpSize(w.dp, h.dp) else null
    } ?: return previewSizes
    val cols = kotlin.math.max(1, kotlin.math.round(real.width.value / 97f).toInt())
    val rows = kotlin.math.max(1, kotlin.math.round(real.height.value / 105f).toInt())
    val label = "${cols}×${rows}"
    return listOf(label to real) + previewSizes.filter { it.first != label }
}

// ---- grouped settings (Android 16 style segmented lists) -----------------------------------

class GroupScope {
    internal val items = mutableListOf<@Composable (ListItemShapes) -> Unit>()
    fun item(content: @Composable (ListItemShapes) -> Unit) { items += content }
}

@Composable
fun SettingsGroup(title: String? = null, build: GroupScope.() -> Unit) {
    val scope = GroupScope().apply(build)
    Column(Modifier.padding(horizontal = 16.dp).padding(top = 24.dp)) {
        if (title != null) {
            Text(
                title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            scope.items.forEachIndexed { i, content -> content(ListItemDefaults.segmentedShapes(i, scope.items.size)) }
        }
    }
}

fun GroupScope.navRow(title: String, summary: String? = null, icon: Int? = null, tag: String? = null, onClick: () -> Unit) = item { shapes ->
    SegmentedListItem(
        onClick = onClick, shapes = shapes,
        modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
        leadingContent = icon?.let { { IconRes(it) } },
        supportingContent = summary?.let { { Text(it) } },
    ) { Text(title) }
}

fun GroupScope.infoRow(title: String, summary: String? = null, icon: Int? = null, trailing: (@Composable () -> Unit)? = null) = item { shapes ->
    SegmentedListItem(
        shapes = shapes,
        leadingContent = icon?.let { { IconRes(it) } },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = trailing,
    ) { Text(title) }
}

fun GroupScope.switchRow(title: String, checked: Boolean, summary: String? = null, tag: String? = null, onChange: (Boolean) -> Unit) = item { shapes ->
    SegmentedListItem(
        onClick = { onChange(!checked) }, shapes = shapes,
        modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    ) { Text(title) }
}

fun <T> GroupScope.choiceRow(title: String, options: List<Pair<T, String>>, selected: T, tag: String? = null, onSelect: (T) -> Unit) = item { shapes ->
    SegmentedListItem(shapes = shapes, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp)) {
        Column {
            Text(title, modifier = Modifier.padding(bottom = 10.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, (value, label) ->
                    SegmentedButton(
                        selected = value == selected, onClick = { onSelect(value) },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        modifier = if (tag != null) Modifier.testTag("${tag}_$i") else Modifier,
                        label = { Text(label, maxLines = 1) },
                    )
                }
            }
        }
    }
}

fun GroupScope.customRow(content: @Composable ColumnScope.() -> Unit) = item { shapes ->
    SegmentedListItem(shapes = shapes, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Column(content = content)
    }
}

@Composable
fun IconRes(res: Int, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    androidx.compose.material3.Icon(painterResource(res), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
}

// ---- palette swatches (Pixel "Wallpaper & style" look) -------------------------------------

@Composable
fun PaletteSwatches(selected: Palette, onSelect: (Palette) -> Unit) {
    val dark = isSystemInDarkTheme()
    val cs = MaterialTheme.colorScheme
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(Palette.entries) { p ->
            val (a, b, c) = if (p == Palette.DYNAMIC) Triple(cs.primary, cs.secondaryContainer, cs.tertiaryContainer)
            else Palettes.scheme(p, dark).let { Triple(it.primary, it.secondaryC, it.primaryC) }
            val sel = p == selected
            Box(
                Modifier
                    .size(52.dp)
                    .border(if (sel) 2.dp else 0.dp, if (sel) cs.primary else Color.Transparent, CircleShape)
                    .padding(5.dp)
                    .clip(CircleShape)
                    .selectable(sel, role = Role.RadioButton) { onSelect(p) }
                    .testTag("palette_${p.name}"),
            ) {
                Canvas(Modifier.size(42.dp)) {
                    drawArc(a, 180f, 180f, true)
                    drawArc(b, 90f, 90f, true)
                    drawArc(c, 0f, 90f, true)
                }
            }
        }
    }
    Text(
        if (selected == Palette.DYNAMIC) "Wallpaper colors" else selected.name.lowercase().replaceFirstChar { it.uppercase() },
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}
