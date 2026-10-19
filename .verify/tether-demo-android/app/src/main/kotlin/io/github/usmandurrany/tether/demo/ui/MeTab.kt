package io.github.usmandurrany.tether.demo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.usmandurrany.tether.core.ConnectionState
import io.github.usmandurrany.tether.demo.TetherSession
import io.github.usmandurrany.tether.im.OwnPresence
import io.github.usmandurrany.tether.im.PushState
import io.github.usmandurrany.tether.im.PushTarget
import io.github.usmandurrany.tether.im.Show
import kotlinx.coroutines.launch

private enum class PresenceChoice(val label: String, val show: Show?) {
    AVAILABLE("Available", null),
    CHAT("Free to chat", Show.CHAT),
    AWAY("Away", Show.AWAY),
    XA("Extended away", Show.XA),
    DND("Do not disturb", Show.DND),
    INVISIBLE("Invisible", null),
}

/** Own presence, what the server supports, push registration, and sign-out. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MeTab(session: TetherSession, onSignOut: (forget: Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    val state by session.client.state.collectAsStateWithLifecycle()
    val live by session.client.session.collectAsStateWithLifecycle()
    val carbons by session.im.carbons.enabled.collectAsStateWithLifecycle()
    val history by session.im.history.available.collectAsStateWithLifecycle()
    val push by session.im.push.state.collectAsStateWithLifecycle()

    var choice by remember { mutableStateOf(PresenceChoice.AVAILABLE) }
    var status by remember { mutableStateOf("") }
    var presenceNote by remember { mutableStateOf<String?>(null) }

    var service by remember { mutableStateOf("") }
    var node by remember { mutableStateOf("") }
    var options by remember { mutableStateOf("") }
    var createNode by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        SectionTitle("Account")
        Text((state as? ConnectionState.Online)?.jid?.toString() ?: session.account.toString())
        Hint(state.label())

        SectionTitle("My presence")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PresenceChoice.entries.forEach {
                FilterChip(selected = choice == it, onClick = { choice = it }, label = { Text(it.label) })
            }
        }
        OutlinedTextField(
            value = status,
            onValueChange = { status = it },
            label = { Text("Status message (optional)") },
            singleLine = true,
            enabled = choice != PresenceChoice.INVISIBLE,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            scope.launch {
                presenceNote = runCatching {
                    if (choice == PresenceChoice.INVISIBLE) {
                        session.im.presences.hide()
                    } else {
                        session.im.presences.set(OwnPresence(show = choice.show, status = status.ifBlank { null }))
                    }
                }.fold({ "Updated. Contacts see this on every connection." }, { "Failed: ${it.message}" })
            }
        }) { Text("Update presence") }
        presenceNote?.let { Hint(it) }

        SectionTitle("What this server supports")
        Feature("Stream Management (XEP-0198)", live?.streamManagement != null, if (live?.resumed == true) "session resumed" else null)
        Feature("Message Carbons (XEP-0280)", carbons, "messages from your other devices")
        Feature("Message Archive (XEP-0313)", history, "history in chats")
        Feature(
            "Push (XEP-0357)",
            push !is PushState.Unsupported,
            if (push is PushState.Unsupported) null else "register below",
        )
        Hint("Tether checks capabilities on each new login and only turns on what the server offers.")

        SectionTitle("Push notifications")
        Hint(
            "Register this device with the server's push service. In a real app the token comes from FCM; " +
                "then call client.wake() when a push arrives. MongooseIM: service pubsub.<domain>, any node name, " +
                "options device_id=<token> and service=fcm, with \"create node\" on.",
        )
        OutlinedTextField(service, { service = it }, label = { Text("Push service JID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(node, { node = it }, label = { Text("Node") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            options,
            { options = it },
            label = { Text("Publish options (key=value per line)") },
            minLines = 2,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )
        SwitchRow("Create node first", createNode, { createNode = it }, hint = "MongooseIM needs this.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = service.isNotBlank() && node.isNotBlank(),
                onClick = {
                    val publish = options.lines().mapNotNull { line ->
                        val i = line.indexOf('=')
                        if (i > 0) line.substring(0, i).trim() to line.substring(i + 1).trim() else null
                    }.toMap()
                    scope.launch { session.registerPush(PushTarget(service.trim(), node.trim(), publish, createNode)) }
                },
            ) { Text("Register") }
            OutlinedButton(onClick = { scope.launch { session.unregisterPush() } }) { Text("Unregister") }
        }
        Hint("State: ${push.describe()}")

        SectionTitle("Session")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onSignOut(false) }) { Text("Disconnect") }
            Button(onClick = { onSignOut(true) }) { Text("Sign out") }
        }
        Hint("Disconnect keeps the saved login. Sign out deletes it from this device.")
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Feature(name: String, on: Boolean, note: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(if (on) "✓" else "–", color = if (on) Online else Offline, modifier = Modifier.padding(end = 10.dp))
        Column {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            if (on && note != null) Hint(note)
        }
    }
}

private fun PushState.describe(): String = when (this) {
    PushState.Off -> "not registered"
    PushState.Unsupported -> "the server has no push support"
    is PushState.Pending -> "registering with ${target.service}"
    is PushState.On -> "on (${target.service}, node ${target.node})"
    is PushState.Failed -> "refused: ${condition ?: "unknown error"}"
}
