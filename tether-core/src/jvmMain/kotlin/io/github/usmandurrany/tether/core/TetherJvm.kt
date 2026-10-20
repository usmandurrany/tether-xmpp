package io.github.usmandurrany.tether.core

import io.github.usmandurrany.tether.transport.ConscryptTlsProvider
import io.github.usmandurrany.tether.transport.EndpointResolver
import io.github.usmandurrany.tether.transport.JndiSrvLookup
import io.github.usmandurrany.tether.transport.JvmSocketTransportFactory
import io.github.usmandurrany.tether.transport.PlatformTlsProvider
import io.github.usmandurrany.tether.transport.TlsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Ready-made wiring for desktop and server JVMs. Android apps use `TetherAndroid` instead. */
public object TetherJvm {
    /** Conscrypt when it is on the classpath (for `tls-exporter`), the JDK's TLS otherwise. */
    public val tlsProvider: TlsProvider by lazy {
        if (ConscryptTlsProvider.isAvailable) ConscryptTlsProvider() else PlatformTlsProvider
    }

    public fun negotiator(config: XmppConfig, logger: TrafficLogger? = null): StreamNegotiator =
        StreamNegotiator(config, JvmSocketTransportFactory(config.tls, tlsProvider), EndpointResolver(JndiSrvLookup()), logger)

    /**
     * A client for a desktop or server JVM, following the network on its own
     * (see [JvmNetworkMonitor]). [store] keeps state between runs; see
     * [FileKeyValueStore]. With [rememberLogin], each login's SCRAM keys (never
     * the password) are saved there too, so the next run needs no password;
     * without it, keys saved earlier are deleted.
     */
    public fun client(
        config: XmppConfig,
        scope: CoroutineScope,
        policy: ClientPolicy = ClientPolicy(),
        store: KeyValueStore? = null,
        logger: TrafficLogger? = null,
        rememberLogin: Boolean = true,
    ): XmppClient {
        val vault = store?.let(::CredentialVault)
        if (!rememberLogin && vault != null) scope.launch { runCatching { vault.clear(config.jid) } }
        val client = XmppClient(negotiator(config, logger), scope, policy, store = store, credentials = vault.takeIf { rememberLogin })
        JvmNetworkMonitor(client).start(client.coroutineScope) // stops with client.close()
        return client
    }
}
