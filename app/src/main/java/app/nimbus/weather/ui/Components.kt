package app.nimbus.weather.ui

import android.content.Context
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.provideContent
import app.nimbus.weather.data.Locations
import app.nimbus.weather.data.Palette
import app.nimbus.weather.data.Place
import app.nimbus.weather.widget.Palettes
import app.nimbus.weather.widget.WeatherContent
import app.nimbus.weather.widget.WidgetState
import kotlinx.coroutines.delay

@Composable
fun NimbusTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialTheme(colorScheme = scheme, content = content)
}

// ---- live widget preview: renders the real Glance RemoteViews -----------------------------

private class PreviewWidget(private val state: WidgetState) : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { WeatherContent(state) }
    }
}

@Composable
fun WidgetPreview(state: WidgetState, size: DpSize, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val rv by produceState<android.widget.RemoteViews?>(null, state, size) {
        delay(60) // debounce rapid slider moves
        value = runCatching { PreviewWidget(state).compose(ctx, size = size) }.getOrNull()
    }
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(cs.primary.copy(alpha = 0.55f), cs.tertiary.copy(alpha = 0.45f), cs.secondary.copy(alpha = 0.6f))))
            .padding(vertical = 24.dp)
            .testTag("widget_preview"),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size)) {
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
}

// ---- settings primitives ---------------------------------------------------------------------

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 20.dp)) {
        Text(
            title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
        )
        Card(
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) { Column(Modifier.padding(vertical = 6.dp)) { content() } }
    }
}

@Composable
fun <T> Segmented(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, tag: String? = null) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (value, text) ->
                SegmentedButton(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    modifier = if (tag != null) Modifier.testTag("${tag}_$i") else Modifier,
                    label = { Text(text, maxLines = 1) },
                )
            }
        }
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null, tag: String? = null) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, modifier = if (tag != null) Modifier.testTag(tag) else Modifier) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@Composable
fun PaletteRow(selected: Palette, onSelect: (Palette) -> Unit) {
    val dark = isSystemInDarkTheme()
    val cs = MaterialTheme.colorScheme
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        items(Palette.entries) { p ->
            val (a, b) = if (p == Palette.DYNAMIC) cs.primaryContainer to cs.primary
            else Palettes.scheme(p, dark).let { it.primaryC to it.primary }
            val sel = p == selected
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(a, b)))
                    .border(if (sel) 3.dp else 0.dp, if (sel) cs.onSurface else Color.Transparent, CircleShape)
                    .clickable { onSelect(p) }
                    .testTag("palette_${p.name}"),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    sel -> Icon(Icons.Default.Check, null, tint = cs.surface)
                    p == Palette.DYNAMIC -> Icon(Icons.Default.AutoAwesome, null, tint = cs.onPrimary, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** "Current location" + city search with recent places. */
@Composable
fun LocationPicker(current: Place?, recent: List<Place>, deviceName: String?, onPick: (Place?) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        if (query.length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(300)
        loading = true
        results = runCatching { Locations.search(query) }.getOrDefault(emptyList())
        loading = false
    }
    Column {
        ListItem(
            headlineContent = { Text("Current location") },
            supportingContent = { Text(deviceName ?: "Uses your phone's approximate location") },
            leadingContent = { Icon(Icons.Default.MyLocation, null) },
            trailingContent = { if (current == null) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { onPick(null) }.testTag("loc_current"),
        )
        (listOfNotNull(current) + recent).distinctBy { it.key }.take(4).forEach { p ->
            PlaceRow(p, selected = current?.key == p.key) { onPick(p) }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("loc_search"),
            placeholder = { Text("Search for a city") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                when {
                    loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    query.isNotEmpty() -> IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear") }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
        )
        results.forEach { p -> PlaceRow(p, false) { onPick(p); query = "" } }
    }
}

@Composable
private fun PlaceRow(p: Place, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(p.name) },
        supportingContent = p.region?.let { { Text(it) } },
        leadingContent = { Icon(Icons.Default.Place, null) },
        trailingContent = { if (selected) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick).testTag("place_${p.name}"),
    )
}

@Composable
fun InfoRow(icon: ImageVector, title: String, body: String, action: (@Composable () -> Unit)? = null) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(body) },
        leadingContent = { Icon(icon, null) },
        trailingContent = action,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

val previewSizes = listOf(
    "Small" to DpSize(170.dp, 170.dp),
    "Medium" to DpSize(352.dp, 170.dp),
    "Large" to DpSize(352.dp, 352.dp),
    "Wide" to DpSize(352.dp, 84.dp),
)
