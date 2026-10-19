package io.github.usmandurrany.tether.demo

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.usmandurrany.tether.core.BareJid
import io.github.usmandurrany.tether.core.ConnectResult
import io.github.usmandurrany.tether.core.MemoryKeyValueStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Signs in through the settings page, trusts the server's self-signed
 * certificate, and chats with a second account, against a real server.
 *
 * Needs two accounts with the same password on a test server, for example:
 * ```
 * ./gradlew connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.xmppHost=10.0.2.2 \
 *   -Pandroid.testInstrumentationRunnerArguments.xmppDomain=localhost \
 *   -Pandroid.testInstrumentationRunnerArguments.xmppPassword=secret
 * ```
 * Accounts `alice@<domain>` and `bob@<domain>` must exist. Skipped without these arguments.
 */
@RunWith(AndroidJUnit4::class)
class ChatEndToEndTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun signInTrustTheCertificateAndChat() {
        val host = args.getString("xmppHost")
        val domain = args.getString("xmppDomain")
        assumeTrue("No test server given", host != null && domain != null)
        val password = args.getString("xmppPassword") ?: "secret"
        val alice = "alice@$domain"
        val bob = "bob@$domain"

        // Sign in as alice through the settings page.
        compose.onNodeWithText("Address (JID)").performTextInput(alice)
        compose.onNodeWithText("Password").performTextInput(password)
        compose.onNodeWithText("Host (optional)").performTextInput(host!!)
        compose.onNodeWithText("Connect").performScrollTo().performClick()

        compose.waitUntil(TIMEOUT) { shows("Trust and connect") || shows("Online") }
        if (shows("Trust and connect")) compose.onNodeWithText("Trust and connect").performClick()
        compose.waitUntil(TIMEOUT) { shows("Online") }

        // Bob signs in with the same SDK, outside the UI.
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as DemoApp
        val pins = app.settingsStore.load().pinnedCertificates
        val bobSession = runBlocking {
            TetherSession.create(
                app,
                ConnectionSettings(jid = bob, host = host, pinnedCertificates = pins),
                password,
                MemoryKeyValueStore(),
                installId = "test",
                scope = app.scope,
            )
        }
        assertTrue(runBlocking { bobSession.connect() } is ConnectResult.Connected)

        try {
            // Alice writes to bob.
            compose.onNodeWithText("Chat with").performTextInput(bob)
            compose.onNodeWithText("Open").performClick()
            compose.onNodeWithTag("message-input").performTextInput("Hello from the test")
            compose.onNodeWithText("Send").performClick()

            val aliceJid = BareJid.parse(alice)
            compose.waitUntil(TIMEOUT) {
                bobSession.conversations.threads.value[aliceJid].orEmpty().any { it.body == "Hello from the test" }
            }
            // Bob's device confirmed it (XEP-0184): two ticks.
            compose.waitUntil(TIMEOUT) { shows("✓✓", substring = true) }

            // Bob answers; alice sees it.
            runBlocking { bobSession.send(aliceJid, "Hi alice") }
            compose.waitUntil(TIMEOUT) { shows("Hi alice") }
        } finally {
            runBlocking { bobSession.signOut(forget = true) }
        }
    }

    private fun shows(text: String, substring: Boolean = false): Boolean =
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private companion object {
        const val TIMEOUT = 30_000L
    }
}
