@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.usmandurrany.tether.demo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.usmandurrany.tether.core.BackgroundMode
import io.github.usmandurrany.tether.core.ConnectionState
import io.github.usmandurrany.tether.demo.ConnectionSettings
import io.github.usmandurrany.tether.demo.DemoApp
import io.github.usmandurrany.tether.demo.SignInResult
import io.github.usmandurrany.tether.demo.TetherSession
import kotlinx.coroutines.launch

/**
 * The generic connection settings page: any XMPP server, found through DNS SRV
 * or given as a host and port, with optional certificate pinning for
 * self-signed servers.
 */
@Composable
fun SettingsScreen(app: DemoApp, canGoBack: Boolean, onBack: () -> Unit, onConnected: () -> Unit) {
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(app.settingsStore.load()) }
    var password by remember { mutableStateOf("") }
    var pinsText by remember { mutableStateOf(settings.pinnedCertificates.joinToString("\n")) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var untrusted by remember { mutableStateOf<SignInResult.UntrustedCertificate?>(null) }
    var savedLogin by remember { mutableStateOf(false) }
    val connecting by app.connecting.collectAsStateWithLifecycle()

    LaunchedEffect(settings.jid) { savedLogin = TetherSession.hasSavedLogin(app.secureStore, settings.jid) }

    fun connect(withSettings: ConnectionSettings) {
        busy = true
        error = null
        scope.launch {
            when (val result = app.signIn(withSettings, password.ifEmpty { null })) {
                SignInResult.Connected -> {
                    password = ""
                    onConnected()
                }
                is SignInResult.Error -> error = result.message
                is SignInResult.UntrustedCertificate -> untrusted = result
            }
            busy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connection settings") },
                navigationIcon = { if (canGoBack) TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Hint("Works with any standards-compliant XMPP server (ejabberd, Prosody, Openfire, MongooseIM…).")

            SectionTitle("Account")
            OutlinedTextField(
                value = settings.jid,
                onValueChange = { settings = settings.copy(jid = it) },
                label = { Text("Address (JID)") },
                placeholder = { Text("alice@example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                placeholder = { if (savedLogin) Text("Saved login — leave empty") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Hint(
                if (savedLogin) "This device has a saved login for this address. Type a password only to replace it."
                else "Used once. Tether saves only the derived login keys, encrypted with the Android Keystore.",
            )
            OutlinedTextField(
                value = settings.resource,
                onValueChange = { settings = settings.copy(resource = it) },
                label = { Text("Resource (optional)") },
                placeholder = { Text("Chosen by the server") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionTitle("Server")
            OutlinedTextField(
                value = settings.host,
                onValueChange = { settings = settings.copy(host = it) },
                label = { Text("Host (optional)") },
                placeholder = { Text("Found through DNS SRV") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = settings.port,
                onValueChange = { settings = settings.copy(port = it.filter(Char::isDigit).take(5)) },
                label = { Text("Port (optional)") },
                placeholder = { Text(if (settings.directTls) "5223" else "5222") },
                singleLine = true,
                enabled = settings.host.isNotBlank(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Hint("Set a host (name or IP address) to skip DNS, for example a server on your LAN.")
            SwitchRow(
                "Direct TLS",
                settings.directTls,
                { settings = settings.copy(directTls = it) },
                hint = "TLS from the first byte (usually port 5223) instead of STARTTLS. Needs a host.",
            )
            OutlinedTextField(
                value = pinsText,
                onValueChange = { pinsText = it },
                label = { Text("Trusted certificate fingerprints (optional)") },
                placeholder = { Text("SHA-256, one per line") },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Hint("For self-signed servers. Leave empty to verify certificates normally. You'll be offered the fingerprint if verification fails.")

            SectionTitle("Login methods")
            SwitchRow("Allow PLAIN", settings.allowPlain, { settings = settings.copy(allowPlain = it) }, hint = "Only over verified TLS. SCRAM is always preferred.")
            SwitchRow("Allow SCRAM-SHA-1", settings.allowScramSha1, { settings = settings.copy(allowScramSha1 = it) }, hint = "For old servers without SHA-256.")

            SectionTitle("In the background")
            BackgroundMode.entries.forEach { mode ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(settings.backgroundMode == mode, role = Role.RadioButton) { settings = settings.copy(backgroundMode = mode) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = settings.backgroundMode == mode, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(mode.title())
                        Hint(mode.hint())
                    }
                }
            }
            SwitchRow("Connect on app start", settings.autoConnect, { settings = settings.copy(autoConnect = it) }, hint = "Uses the saved login.")

            SectionTitle("Debug log portal (optional)")
            Hint("Uploads redacted stanza logs to a tether-debug portal while testing. Leave empty to keep logs on the device.")
            OutlinedTextField(
                value = settings.logEndpoint,
                onValueChange = { settings = settings.copy(logEndpoint = it) },
                label = { Text("Portal URL") },
                placeholder = { Text("https://logs.example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = settings.logIngestKey,
                onValueChange = { settings = settings.copy(logIngestKey = it) },
                label = { Text("Ingest key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = settings.logLabel,
                onValueChange = { settings = settings.copy(logLabel = it) },
                label = { Text("Label (optional)") },
                placeholder = { Text("beta-1") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            SwitchRow("Hide message text in logs", settings.redactBodies, { settings = settings.copy(redactBodies = it) })

            Spacer(Modifier.height(20.dp))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = !busy && settings.jid.isNotBlank(),
                    onClick = {
                        val pins = parsePins(pinsText)
                        if (pins == null) {
                            error = "Each fingerprint must be 64 hex characters (colons and spaces are fine)."
                        } else {
                            settings = settings.copy(pinnedCertificates = pins)
                            connect(settings)
                        }
                    },
                ) { Text(if (canGoBack) "Reconnect with these settings" else "Connect") }
                if (busy) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    val state = connecting?.client?.state?.collectAsState()?.value
                    if (state != null && state != ConnectionState.Idle) Hint(state.label())
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    untrusted?.let { offer ->
        AlertDialog(
            onDismissRequest = { untrusted = null },
            title = { Text("Trust this certificate?") },
            text = {
                Column {
                    Text("${offer.message} If this is your own server with a self-signed certificate, check that its SHA-256 fingerprint is:")
                    Spacer(Modifier.height(8.dp))
                    Text(offer.sha256.chunked(2).joinToString(":"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text("Only trust it if it matches. Tether will then accept this certificate and no other.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    untrusted = null
                    val pins = (parsePins(pinsText) ?: emptySet()) + offer.sha256
                    pinsText = pins.joinToString("\n")
                    settings = settings.copy(pinnedCertificates = pins)
                    connect(settings)
                }) { Text("Trust and connect") }
            },
            dismissButton = { TextButton(onClick = { untrusted = null }) { Text("Cancel") } },
        )
    }
}

/** Fingerprints in any common notation, normalised to lowercase hex. Null if one is malformed. */
private fun parsePins(text: String): Set<String>? {
    val pins = text.lines().map { line -> line.filter { it.isLetterOrDigit() }.lowercase() }.filter { it.isNotEmpty() }
    return if (pins.all { it.matches(Regex("[0-9a-f]{64}")) }) pins.toSet() else null
}

private fun BackgroundMode.title(): String = when (this) {
    BackgroundMode.CLOSE -> "Disconnect (push notifications)"
    BackgroundMode.DETACH -> "Detach, resume on return"
    BackgroundMode.STAY_CONNECTED -> "Stay connected"
}

private fun BackgroundMode.hint(): String = when (this) {
    BackgroundMode.CLOSE -> "Closes cleanly 5 s after the app is hidden, so the server stores messages and sends push."
    BackgroundMode.DETACH -> "Drops the socket but keeps the session resumable, for a fast return."
    BackgroundMode.STAY_CONNECTED -> "Keeps the connection open and tells the server the app is inactive (CSI)."
}
