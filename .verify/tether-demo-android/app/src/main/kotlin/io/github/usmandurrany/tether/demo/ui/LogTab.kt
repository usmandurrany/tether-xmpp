package io.github.usmandurrany.tether.demo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.usmandurrany.tether.debug.LogEntry
import io.github.usmandurrany.tether.debug.LogLevel
import io.github.usmandurrany.tether.demo.TetherSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import io.github.usmandurrany.tether.debug.Direction as LogDirection

/**
 * What tether-debug recorded: every stanza in and out (credentials always
 * redacted) and connection events. Also in Logcat under the tag "Tether".
 */
@Composable
fun LogTab(session: TetherSession) {
    val entries by session.logs.entries.collectAsStateWithLifecycle()
    var stanzas by rememberSaveable { mutableStateOf(true) }
    var follow by rememberSaveable { mutableStateOf(true) }
    val shown = if (stanzas) entries else entries.filter { !it.isStanza }
    val list = rememberLazyListState()
    val remote = session.remoteLogs

    LaunchedEffect(shown.size, follow) {
        if (follow && shown.isNotEmpty()) list.scrollToItem(shown.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = stanzas, onClick = { stanzas = !stanzas }, label = { Text("Stanzas") })
            TextButton(onClick = { follow = !follow }) { Text(if (follow) "Following" else "Follow") }
            TextButton(onClick = { session.logs.clear() }) { Text("Clear") }
        }
        if (remote != null) {
            val stats by remote.stats.collectAsStateWithLifecycle()
            Hint(
                "Portal ${remote.config.endpoint}: ${stats.uploaded} uploaded, ${stats.queued} queued, ${stats.dropped} dropped" +
                    (stats.lastError?.let { " · last error: $it" } ?: ""),
                Modifier.padding(horizontal = 16.dp),
            )
        }
        HorizontalDivider()
        LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
            items(shown, key = { it.seq }) { LogLine(it) }
        }
    }
}

private val timeFormat = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

@Composable
private fun LogLine(entry: LogEntry) {
    val arrow = when (entry.direction) {
        LogDirection.SENT -> "→"
        LogDirection.RECEIVED -> "←"
        null -> entry.tag + ":"
    }
    val color = when {
        entry.level >= LogLevel.ERROR -> MaterialTheme.colorScheme.error
        entry.direction == LogDirection.SENT -> MaterialTheme.colorScheme.primary
        entry.direction == LogDirection.RECEIVED -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        "${timeFormat.get()!!.format(Date(entry.timestampMillis))} $arrow ${entry.text}" + (entry.error?.let { "\n$it" } ?: ""),
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
    )
}
