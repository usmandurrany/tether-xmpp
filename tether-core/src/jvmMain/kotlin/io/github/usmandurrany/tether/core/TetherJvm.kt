package io.github.usmandurrany.tether.core

import io.github.usmandurrany.tether.transport.ConscryptTlsProvider
import io.github.usmandurrany.tether.transport.EndpointResolver
import io.github.usmandurrany.tether.transport.JndiSrvLookup
import io.github.usmandurrany.tether.transport.JvmSocketTransportFactory
import io.github.usmandurrany.tether.transport.PlatformTlsProvider
import io.github.usmandurrany.tether.transport.TlsProvider
import kotlinx.coroutines.CoroutineScope

/** Ready-made wiring for desktop and server JVMs. Android apps use `TetherAndroid` instead. */
public object TetherJvm {
    /** Conscrypt when it is on the classpath (for `tls-exporter`), the JDK's TLS otherwise. */
    public val tlsProvider: TlsProvider by lazy {
        if (ConscryptTlsProvider.isAvailable) ConscryptTlsProvider() else PlatformTlsProvider
    }

    public fun negotiator(config: XmppConfig, logger: TrafficLogger? = null): StreamNegotiator =
        StreamNegotiator(config, JvmSocketTransportFactory(config.tls, tlsProvider), EndpointResolver(JndiSrvLookup()), logger)

    /** [store] keeps state and login keys between runs; see [FileKeyValueStore]. */
    public fun client(
        config: XmppConfig,
        scope: CoroutineScope,
        policy: ClientPolicy = ClientPolicy(),
        store: KeyValueStore? = null,
        logger: TrafficLogger? = null,
    ): XmppClient = XmppClient(
        negotiator(config, logger),
        scope,
        policy,
        store = store,
        credentials = store?.let(::CredentialVault),
    )
}
