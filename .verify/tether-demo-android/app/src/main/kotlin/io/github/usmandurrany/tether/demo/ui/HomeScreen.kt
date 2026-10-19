@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.usmandurrany.tether.demo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.usmandurrany.tether.core.BareJid
import io.github.usmandurrany.tether.core.ConnectionState
import io.github.usmandurrany.tether.demo.TetherSession
import kotlinx.coroutines.launch

private val tabs = listOf("Chats", "Contacts", "Me", "Log")

@Composable
fun HomeScreen(
    session: TetherSession,
    onOpenChat: (BareJid) -> Unit,
    onSettings: () -> Unit,
    onSignOut: (forget: Boolean) -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val state by session.client.state.collectAsStateWithLifecycle()
    val requests by session.subscriptionRequests.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(session.account.toString(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Dot(state.color())
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    state.label(),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    actions = {
                        if (state is ConnectionState.Terminal) {
                            TextButton(onClick = { scope.launch { session.connect() } }) { Text("Retry") }
                        }
                        TextButton(onClick = onSettings) { Text("Settings") }
                    },
                )
                TabRow(selectedTabIndex = tab) {
                    tabs.forEachIndexed { index, title ->
                        val label = if (index == 1 && requests.isNotEmpty()) "$title (${requests.size})" else title
                        Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label, maxLines = 1) })
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> ChatsTab(session, onOpenChat)
                1 -> ContactsTab(session, onOpenChat)
                2 -> MeTab(session, onSignOut)
                else -> LogTab(session)
            }
        }
    }
}
