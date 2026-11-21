@file:OptIn(ExperimentalMaterial3Api::class)

package app.helpmecalendar.ui

import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.helpmecalendar.data.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What the widget has been doing: refreshes, skipped updates, errors. Last 3 days. */
class LogsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { HelpMeCalendarTheme { LogsScreen(onBack = { finish() }) } }
    }
}

@Composable
private fun LogsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<AppLog.Entry>>(emptyList()) }
    var confirmClear by remember { mutableStateOf(false) }
    fun reload() = scope.launch { entries = withContext(Dispatchers.IO) { AppLog.read(ctx) } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reload() }

    val text = remember(entries) {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
        // [datetime] [LEVEL] Area: message
        entries.asReversed().joinToString("\n") { e ->
            val level = when (e.level) { AppLog.Level.I -> "INFO"; AppLog.Level.W -> "WARN"; AppLog.Level.E -> "ERROR" }
            "[${fmt.format(Instant.ofEpochMilli(e.time))}] [$level] ${e.tag}: ${e.message}"
        }
    }
    val scroll = rememberScrollState()
    LaunchedEffect(text) { scroll.scrollTo(scroll.maxValue) } // open at the newest line
    val bg = MaterialTheme.colorScheme.surfaceContainer

    Scaffold(
        containerColor = bg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Logs")
                        Text("Last 3 days · ${entries.size} events", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    TextButton(
                        onClick = {
                            val header = "Help Me Clock v${app.helpmecalendar.BuildConfig.VERSION_NAME} · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MODEL}\n"
                            val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                            cm?.setPrimaryClip(android.content.ClipData.newPlainText("Help Me Clock logs", header + text))
                            // Android 13+ shows its own "Copied" confirmation.
                            if (android.os.Build.VERSION.SDK_INT < 33) android.widget.Toast.makeText(ctx, "Logs copied", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        enabled = entries.isNotEmpty(), modifier = Modifier.testTag("logs_copy"),
                    ) { Text("Copy") }
                    TextButton(onClick = { confirmClear = true }, enabled = entries.isNotEmpty(), modifier = Modifier.testTag("logs_clear")) { Text("Clear") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bg),
            )
        },
    ) { pad ->
        SelectionContainer(Modifier.fillMaxSize().padding(pad)) {
            Text(
                text.ifEmpty { "No events yet." },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, lineHeight = 17.sp),
                color = MaterialTheme.colorScheme.onSurface,
                // Wrap long lines so everything fits the screen width.
                modifier = Modifier.fillMaxSize().verticalScroll(scroll)
                    .padding(horizontal = 16.dp, vertical = 8.dp).testTag("logs_text"),
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear logs?") },
            text = { Text("This deletes all logged events on this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch { withContext(Dispatchers.IO) { AppLog.clear(ctx) }; reload() }
                }, modifier = Modifier.testTag("logs_clear_confirm")) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
