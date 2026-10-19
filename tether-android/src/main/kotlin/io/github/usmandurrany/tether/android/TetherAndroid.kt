package io.github.usmandurrany.tether.android

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.usmandurrany.tether.core.ClientPolicy
import io.github.usmandurrany.tether.core.ConnectionControls
import io.github.usmandurrany.tether.core.CredentialVault
import io.github.usmandurrany.tether.core.KeyValueStore
import io.github.usmandurrany.tether.core.XmppClient
import io.github.usmandurrany.tether.core.StreamNegotiator
import io.github.usmandurrany.tether.core.TrafficLogger
import io.github.usmandurrany.tether.core.XmppConfig
import io.github.usmandurrany.tether.transport.ConscryptTlsProvider
import io.github.usmandurrany.tether.transport.DnsSrvLookup
import io.github.usmandurrany.tether.transport.EndpointResolver
import io.github.usmandurrany.tether.transport.JvmSocketTransportFactory
import io.github.usmandurrany.tether.transport.PlatformTlsProvider
import io.github.usmandurrany.tether.transport.TlsConfig
import io.github.usmandurrany.tether.transport.TlsProvider
import io.github.usmandurrany.tether.transport.XmppTransportFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Android wiring for Tether: transport, DNS and TLS that work on Android, and
 * the app lifecycle and network monitor fed into a connection.
 *
 * ```
 * val store = TetherAndroid.secureStore(context)
 * val vault = CredentialVault(store)
 * val config = XmppConfig(jid, password = typedPassword ?: null, scramKeys = vault.loadKeys(jid))
 * val client = TetherAndroid.client(context, config, appScope)
 * val im = InstantMessaging(client)
 * client.connect()
 * ```
 */
public object TetherAndroid {
    /** Conscrypt when its native library loads (for `tls-exporter`), the platform's TLS otherwise. */
    public val tlsProvider: TlsProvider by lazy {
        if (ConscryptTlsProvider.isAvailable) ConscryptTlsProvider() else PlatformTlsProvider
    }

    public fun transportFactory(tls: TlsConfig): XmppTransportFactory = JvmSocketTransportFactory(tls, tlsProvider)

    /** SRV lookups against the DNS servers of the current default network. */
    public fun endpointResolver(context: Context): EndpointResolver {
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        return EndpointResolver(
            DnsSrvLookup({
                val network = connectivity.activeNetwork
                network?.let { connectivity.getLinkProperties(it) }?.dnsServers.orEmpty()
                    .map { java.net.InetSocketAddress(it, 53) }
            }),
        )
    }

    public fun negotiator(context: Context, config: XmppConfig, logger: TrafficLogger? = null): StreamNegotiator =
        StreamNegotiator(config, transportFactory(config.tls), endpointResolver(context.applicationContext), logger)

    /** Encrypted storage for Tether's state and credentials (Android Keystore). */
    public fun secureStore(context: Context, name: String = "tether"): KeyValueStore = AndroidSecureStore(context, name)

    /**
     * A client wired for Android: Android DNS and TLS, the app lifecycle and
     * network fed in, state and login keys kept in [store]. Keep one per
     * account for the life of the process, in an application-wide scope.
     */
    public fun client(
        context: Context,
        config: XmppConfig,
        scope: CoroutineScope,
        policy: ClientPolicy = ClientPolicy(),
        store: KeyValueStore = secureStore(context),
        logger: TrafficLogger? = null,
    ): XmppClient {
        val client = XmppClient(
            negotiator(context, config, logger),
            scope,
            policy,
            store = store,
            credentials = CredentialVault(store),
        )
        bind(context, client, scope)
        return client
    }

    /**
     * Feeds [controls] from the process lifecycle (foreground when any activity
     * is started) and the default network. Call once per connection; close the
     * result to stop, for example on logout.
     */
    public fun bind(context: Context, controls: ConnectionControls, scope: CoroutineScope): AutoCloseable {
        val app = context.applicationContext
        val network = AndroidNetworkMonitor(app, scope) { controls.networkChanged(it.available, it.id, it.kind) }
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = controls.setForeground(true)
            override fun onStop(owner: LifecycleOwner) = controls.setForeground(false)
        }
        network.start()
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        scope.launch(Dispatchers.Main.immediate) {
            // A process started in the background (by a push, a job) is not in the foreground.
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controls.setForeground(false)
            lifecycle.addObserver(observer)
        }
        return AutoCloseable {
            network.stop()
            scope.launch(Dispatchers.Main.immediate) { lifecycle.removeObserver(observer) }
        }
    }
}
