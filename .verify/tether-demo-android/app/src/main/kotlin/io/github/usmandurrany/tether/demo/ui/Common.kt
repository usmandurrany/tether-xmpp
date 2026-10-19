package io.github.usmandurrany.tether.demo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.usmandurrany.tether.core.ConnectionState
import io.github.usmandurrany.tether.demo.TetherSession
import io.github.usmandurrany.tether.im.ContactPresence
import io.github.usmandurrany.tether.im.Show

/** A short label for the connection state, for the top bar. */
fun ConnectionState.label(): String = when (this) {
    ConnectionState.Idle -> "Offline"
    ConnectionState.WaitingForNetwork -> "Waiting for network"
    is ConnectionState.Connecting -> if (attempt > 1) "Connecting (attempt $attempt)" else "Connecting"
    is ConnectionState.Online -> "Online"
    is ConnectionState.Backoff -> "Reconnecting in ${(delayMillis + 999) / 1000}s"
    ConnectionState.Paused -> "Paused in background"
    is ConnectionState.Terminal -> "Stopped: ${TetherSession.describe(error)}"
}

@Composable
fun ConnectionState.color(): Color = when (this) {
    is ConnectionState.Online -> Online
    is ConnectionState.Terminal -> MaterialTheme.colorScheme.error
    else -> Away
}

val Online = Color(0xFF2E7D32)
val Away = Color(0xFFF9A825)
val Busy = Color(0xFFC62828)
val Offline = Color(0xFF9E9E9E)

/** The best presence of a contact's online devices, or null when offline. */
fun List<ContactPresence>?.best(): ContactPresence? = this?.maxWithOrNull(
    compareBy<ContactPresence> { it.priority }.thenBy { if (it.show == null || it.show == Show.CHAT) 1 else 0 },
)

fun presenceColor(presence: ContactPresence?): Color = when (presence?.show) {
    null -> if (presence == null) Offline else Online
    Show.CHAT -> Online
    Show.AWAY, Show.XA -> Away
    Show.DND -> Busy
}

fun presenceText(presence: ContactPresence?): String {
    if (presence == null) return "Offline"
    val show = when (presence.show) {
        null -> "Available"
        Show.CHAT -> "Free to chat"
        Show.AWAY -> "Away"
        Show.XA -> "Extended away"
        Show.DND -> "Do not disturb"
    }
    return if (presence.status.isNullOrBlank()) show else "$show · ${presence.status}"
}

@Composable
fun Dot(color: Color, modifier: Modifier = Modifier) {
    Spacer(modifier.size(10.dp).clip(CircleShape).background(color))
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
    )
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, hint: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}
