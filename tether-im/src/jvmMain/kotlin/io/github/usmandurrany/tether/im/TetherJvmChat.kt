package io.github.usmandurrany.tether.im

import io.github.usmandurrany.tether.core.BareJid
import io.github.usmandurrany.tether.core.ClientPolicy
import io.github.usmandurrany.tether.core.ConnectionOptions
import io.github.usmandurrany.tether.core.CredentialVault
import io.github.usmandurrany.tether.core.FileKeyValueStore
import io.github.usmandurrany.tether.core.KeyValueStore
import io.github.usmandurrany.tether.core.TetherJvm
import io.github.usmandurrany.tether.core.TrafficLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Path

/**
 * A ready [TetherChat] for a desktop or server JVM: the simple way to start.
 *
 * ```
 * val chat = TetherJvm.chat(BareJid.parse("alice@example.com"), password)  // later runs: no password
 * chat.connect()
 * ```
 *
 * Logs in with [password] when given, otherwise with the keys the last login
 * saved in [store] (throws [IllegalArgumentException] when there are none).
 * State and login keys are kept in [store]: by default a file only this user
 * can read, `~/.tether/<account>.properties`. Messages are kept in
 * [messageStore], by default `~/.tether/<account>.messages`. The client runs on its own
 * background threads until [TetherChat.close].
 *
 * Network changes are followed on their own. Tell the chat when the window
 * loses or gains focus with `chat.client.setForeground(...)`, so read markers
 * only go out while the user can see the chat.
 *
 * With [rememberLogin] off, the login keys are not kept (and keys from an
 * earlier run are deleted), so every run needs the password.
 */
public suspend fun TetherJvm.chat(
    jid: BareJid,
    password: String? = null,
    features: ChatFeatures = ChatFeatures(),
    options: ConnectionOptions = ConnectionOptions(),
    policy: ClientPolicy = ClientPolicy(),
    store: KeyValueStore = FileKeyValueStore(defaultStorePath(jid, "properties")),
    /** Keeps messages, ticks and deletions between runs; null keeps them in memory only. */
    messageStore: MessageStore? = FileMessageStore(defaultStorePath(jid, "messages")),
    logger: TrafficLogger? = null,
    rememberLogin: Boolean = true,
): TetherChat {
    val config = options.configFor(jid, password, CredentialVault(store))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val client = client(config, scope, policy, store, logger, rememberLogin)
    return TetherChat(client, features, store, messageStore)
}

private fun defaultStorePath(jid: BareJid, extension: String): Path =
    Path.of(System.getProperty("user.home"), ".tether", "$jid.$extension")
