@file:OptIn(ExperimentalFoundationApi::class)

package io.github.usmandurrany.tether.demo.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.usmandurrany.tether.core.BareJid
import io.github.usmandurrany.tether.demo.Delivery
import io.github.usmandurrany.tether.demo.TetherSession
import io.github.usmandurrany.tether.im.RosterItem
import io.github.usmandurrany.tether.im.Subscription
import kotlinx.coroutines.launch

/** Conversations of this run, newest first, plus a field to start a new one. */
@Composable
fun ChatsTab(session: TetherSession, onOpenChat: (BareJid) -> Unit) {
    val threads by session.conversations.threads.collectAsStateWithLifecycle()
    val unread by session.conversations.unread.collectAsStateWithLifecycle()
    val roster by session.im.roster.items.collectAsStateWithLifecycle()
    val presences by session.im.presences.contacts.collectAsStateWithLifecycle()
    var address by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val sorted = threads.entries
        .filter { it.value.isNotEmpty() }
        .sortedByDescending { it.value.last().timeMillis }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it; error = null },
                    label = { Text("Chat with") },
                    placeholder = { Text("bob@example.com") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = if (error != null) { { Text(error.orEmpty()) } } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    val jid = parseContact(address)
                    if (jid == null) error = "Enter an address like bob@example.com" else { address = ""; onOpenChat(jid) }
                }) { Text("Open") }
            }
        }
        if (sorted.isEmpty()) {
            item { Empty("No messages yet. Open a contact, or type an address above.") }
        }
        items(sorted, key = { it.key.toString() }) { (peer, messages) ->
            val last = messages.last()
            ListRow(
                title = roster[peer]?.name ?: peer.toString(),
                subtitle = buildString {
                    if (last.outgoing) append("You: ")
                    append(last.body)
                    if (last.delivery == Delivery.FAILED) append("  (failed)")
                },
                dot = presenceColor(presences[peer].best()),
                badge = unread[peer]?.takeIf { it > 0 },
                onClick = { onOpenChat(peer) },
            )
        }
    }
}

/** The roster with presence, pending subscription requests, and add/remove. */
@Composable
fun ContactsTab(session: TetherSession, onOpenChat: (BareJid) -> Unit) {
    val roster by session.im.roster.items.collectAsStateWithLifecycle()
    val presences by session.im.presences.contacts.collectAsStateWithLifecycle()
    val requests by session.subscriptionRequests.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<RosterItem?>(null) }

    val contacts = roster.values.sortedWith(
        compareBy<RosterItem> { presences[it.jid] == null }.thenBy { (it.name ?: it.jid.toString()).lowercase() },
    )

    LazyColumn(Modifier.fillMaxSize()) {
        items(requests.toList(), key = { "request-$it" }) { jid ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("$jid wants to see when you're online.")
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { scope.launch { session.answerRequest(jid, approve = true) } }) { Text("Approve") }
                        OutlinedButton(onClick = { scope.launch { session.answerRequest(jid, approve = false) } }) { Text("Deny") }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Hint("${roster.size} contacts · long-press to remove", Modifier.weight(1f))
                TextButton(onClick = { adding = true }) { Text("Add contact") }
            }
        }
        if (contacts.isEmpty()) item { Empty("Your contact list is empty.") }
        items(contacts, key = { it.jid.toString() }) { item ->
            val presence = presences[item.jid].best()
            ListRow(
                title = item.name ?: item.jid.toString(),
                subtitle = buildString {
                    if (item.name != null) append(item.jid).append(" · ")
                    append(presenceText(presence))
                    subscriptionNote(item)?.let { append(" · ").append(it) }
                },
                dot = presenceColor(presence),
                onClick = { onOpenChat(item.jid) },
                onLongClick = { removing = item },
            )
        }
    }

    if (adding) AddContactDialog(onDismiss = { adding = false }) { jid, name ->
        adding = false
        scope.launch { runCatching { session.addContact(jid, name) } }
    }

    removing?.let { item ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${item.name ?: item.jid}?") },
            text = { Text("They are removed from your contacts and presence sharing ends both ways.") },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    scope.launch { runCatching { session.removeContact(item.jid) } }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

private fun subscriptionNote(item: RosterItem): String? = when {
    item.pendingOut -> "request sent"
    item.subscription == Subscription.NONE -> "not sharing presence"
    item.subscription == Subscription.FROM -> "they can't share theirs yet"
    item.subscription == Subscription.TO -> "you don't share yours"
    else -> null
}

@Composable
private fun AddContactDialog(onDismiss: () -> Unit, onAdd: (BareJid, String?) -> Unit) {
    var address by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    val jid = parseContact(address)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add contact") },
        text = {
            Column {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Address") },
                    placeholder = { Text("bob@example.com") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name (optional)") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                Hint("Also asks them to share their presence with you.")
            }
        },
        confirmButton = { TextButton(enabled = jid != null, onClick = { jid?.let { onAdd(it, name) } }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

fun parseContact(text: String): BareJid? =
    runCatching { BareJid.parse(text.trim()) }.getOrNull()?.takeIf { it.local != null }

@Composable
fun ListRow(
    title: String,
    subtitle: String,
    dot: androidx.compose.ui.graphics.Color,
    badge: Int? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (onLongClick != null) it.combinedClickable(onClick = onClick, onLongClick = onLongClick) else it.clickable(onClick = onClick) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(dot)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (badge != null) Badge { Text(badge.toString()) }
    }
    HorizontalDivider(Modifier.padding(start = 38.dp))
}

@Composable
fun Empty(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Hint(text)
    }
}
