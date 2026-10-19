# Integrating Tether

This guide shows how to add Tether to an Android or JVM app: connect an account, keep it connected, and send and receive messages.

## Modules

| Module | What it gives you |
| --- | --- |
| `tether-core` | `XmppClient`: login, reconnection, stream resumption, outbox, saved state |
| `tether-im` | Roster, presence, messages (receipts, typing, carbons), history (MAM), push registration |
| `tether-android` | Android wiring: DNS, TLS (Conscrypt), app lifecycle, network changes, encrypted storage |

`tether-xml`, `tether-sasl`, `tether-crypto` and `tether-transport` come in as dependencies of `tether-core`.

## Adding the dependency

Tether is not on Maven Central yet. Publish it to your local Maven repository:

```sh
./gradlew publishToMavenLocal
```

Then, in your app:

```kotlin
repositories { mavenLocal() }

dependencies {
    implementation("io.github.usmandurrany.tether:tether-android:0.1.0-SNAPSHOT") // Android apps
    implementation("io.github.usmandurrany.tether:tether-im:0.1.0-SNAPSHOT")
}
```

On a desktop or server JVM, use `tether-core` instead of `tether-android`. Add `org.conscrypt:conscrypt-openjdk-uber:2.7.0` as well if you want the stronger `tls-exporter` channel binding on TLS 1.3; without it the JDK's TLS is used with `tls-server-end-point`.

## Android

Create one client per account for the life of the process, in an application-wide scope (for example in your `Application` class or a DI singleton):

```kotlin
class ChatApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var client: XmppClient
    lateinit var im: InstantMessaging

    suspend fun signIn(jid: String, typedPassword: String?): ConnectResult {
        val account = BareJid.parse(jid)
        val store = TetherAndroid.secureStore(this)
        val config = XmppConfig(
            jid = account,
            password = typedPassword,                             // only on first login
            scramKeys = CredentialVault(store).loadKeys(account), // later logins
        )
        client = TetherAndroid.client(this, config, scope, store = store)
        im = InstantMessaging(client, rosterStore = StoredRoster(store, account))
        return client.connect()
    }
}
```

`TetherAndroid.client` does the platform wiring for you:

- It follows the app lifecycle. Five seconds after the app goes to the background the connection is closed cleanly, so the server sends push notifications. It reconnects (or resumes) when the app comes back.
- It follows network changes. A lost network pauses retries, and a new one reconnects at once.
- It keeps state in the Android Keystore-encrypted store: the stream-resumption token, unconfirmed and unsent messages, keepalive timings, and the SCRAM login keys.

The password is never stored. After the first successful login, Tether saves the derived SCRAM keys, and `CredentialVault.loadKeys` returns them for later logins. On logout, call `client.disconnect()`, `client.close()` and `CredentialVault(store).clear(account)`.

Permissions (`INTERNET`, `ACCESS_NETWORK_STATE`) come from the library manifest.

## JVM (desktop, server, tools)

```kotlin
val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
val store = FileKeyValueStore(Path.of(System.getProperty("user.home"), ".myapp", "tether.properties"))
val client = TetherJvm.client(XmppConfig(BareJid.parse("alice@example.com"), password = secret), scope, store = store)
val im = InstantMessaging(client)
client.connect()
```

Desktop apps should call `client.setForeground(...)` and `client.networkChanged(...)` themselves if they know about window focus or network changes. Without them the client behaves as a foreground app on an always-available network.

## Connection state

```kotlin
scope.launch {
    client.state.collect { state ->
        when (state) {
            is ConnectionState.Online -> showOnline(state.jid)
            is ConnectionState.Connecting, is ConnectionState.Backoff -> showConnecting()
            ConnectionState.WaitingForNetwork -> showNoNetwork()
            ConnectionState.Paused -> {}                      // app in the background
            is ConnectionState.Terminal -> showError(state.error) // bad password, certificate, conflict
            ConnectionState.Idle -> {}
        }
    }
}
```

`connect()` returns once the first attempt finishes, with `ConnectResult.Connected`, `Failed` or `Cancelled`. After that, Tether keeps reconnecting on its own until `disconnect()`. Only a `Terminal` state needs the user (or a new `connect()`).

## Messages

```kotlin
scope.launch {
    im.messaging.events.collect { event ->
        when (event) {
            is MessageEvent.Received -> save(event.message)       // live, carbons and offline messages
            is MessageEvent.SentToServer -> markSent(event.id)    // the server confirmed it (XEP-0198)
            is MessageEvent.Delivered -> markDelivered(event.id)  // the contact's device confirmed it (XEP-0184)
            is MessageEvent.Failed -> markFailed(event.id, event.condition)
            is MessageEvent.ChatStateChanged -> showTyping(event.peer, event.state)
        }
    }
}

val id = im.messaging.send(BareJid.parse("bob@example.com"), "Hi Bob")
im.messaging.sendChatState(bob, ChatState.COMPOSING)
```

`send` works while offline: the message waits in the outbox (kept across restarts) and goes out on the next connection. Save the returned id with the message so you can match the later events. `ChatMessage.serverId` is the archive id; use it to de-duplicate messages that arrive both live and from history.

## Contacts and presence

```kotlin
im.roster.items.collect { contacts -> render(contacts.values) }
im.presences.contacts.collect { online -> renderOnline(online) }
im.presences.subscriptions.collect { if (it is SubscriptionEvent.Requested) askUser(it.from) }

im.roster.set(bob, name = "Bob", groups = setOf("Friends"))
im.presences.request(bob)      // ask to see Bob's presence
im.presences.approve(bob)      // let Bob see ours
im.presences.set(OwnPresence(show = Show.AWAY, status = "In a meeting"))
```

## History

```kotlin
if (im.history.available.value) {
    var page = im.history.fetch(with = bob)                 // newest page
    while (!page.complete) page = im.history.fetch(with = bob, before = page.first)
}
```

History needs `mod_mam` (MongooseIM, ejabberd) or the Openfire Monitoring plugin on the server. `available` is false otherwise.

## Push notifications

Get a token from FCM (or APNs), then register it with the server's push service. The fields differ by server:

```kotlin
// MongooseIM (mod_event_pusher_push)
im.push.register(PushTarget(
    service = "pubsub.example.com",
    node = deviceId,                                  // any stable id per device
    publishOptions = mapOf("device_id" to fcmToken, "service" to "fcm"),
    createNode = true,
))
```

For ejabberd and Openfire, `service` is the push app server's JID and `node` and `publishOptions` are what it gave this device (see the `PushTarget` docs). Watch `im.push.state`: `Unsupported` means the server has no XEP-0357 support. Tether re-registers on every new login and checks the server's capabilities first.

When a push arrives, call `client.wake()`: it checks a live connection at once, or skips the wait before the next retry.

## Login options

- SCRAM-SHA-256 and SCRAM-SHA-512, with channel binding when the server supports it, are the defaults.
- PLAIN is off by default. Turn it on with `XmppConfig(sasl = SaslConfig(allowPlain = true))`. It still requires verified TLS.
- Self-signed server certificates: pin them with `TlsConfig(pinnedCertificateSha256 = setOf("<sha-256 hex>"))`. `./gradlew :tools:probe:run` prints the fingerprint.

## Server checklist

Tether checks the server's capabilities before turning on any feature, so a missing module only disables that feature. For the full feature set, enable:

- STARTTLS, required (or direct TLS on 5223)
- Stream management (XEP-0198), with resumption
- Message archive (XEP-0313)
- Message carbons (XEP-0280)
- Client state indication (XEP-0352)
- Push (XEP-0357) and a push app server for FCM/APNs
