@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.nimbus.weather.ui

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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.provideContent
import app.nimbus.weather.data.Palette
import app.nimbus.weather.widget.Palettes
import app.nimbus.weather.widget.WeatherContent
import app.nimbus.weather.widget.WidgetState
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
fun NimbusTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    val typography = remember { Typography().withFamily(systemDisplayFamily()) }
    MaterialExpressiveTheme(colorScheme = scheme, typography = typography, content = content)
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
    val rv by produceState<RemoteViews?>(null, state, size) {
        delay(50) // coalesce rapid slider changes
        value = runCatching { PreviewWidget(state).compose(ctx, size = size) }.getOrNull()
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(vertical = 20.dp)
            .testTag("widget_preview"),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { FrameLayout(it) },
            update = { fl ->
                fl.removeAllViews()
                rv?.let { runCatching { fl.addView(it.apply(fl.context, fl)) } }
            },
            modifier = Modifier.size(size),
        )
    }
}

/** Real Pixel launcher spans (~97dp per column, ~105dp per row). */
val previewSizes = listOf(
    "2×2" to DpSize(190.dp, 210.dp),
    "4×1" to DpSize(380.dp, 105.dp),
    "4×2" to DpSize(380.dp, 210.dp),
    "4×3" to DpSize(380.dp, 316.dp),
)

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
