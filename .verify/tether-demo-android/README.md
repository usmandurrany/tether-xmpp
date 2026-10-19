# Tether demo for Android

A small Android chat app that uses every feature of the [Tether XMPP SDK](https://github.com/usmandurrany/tether-xmpp),
for developers to read and copy from. It connects to any standards-compliant XMPP server
(ejabberd, Prosody, Openfire, MongooseIM…) through a generic connection settings page.

| Screen | What it shows | SDK API |
| --- | --- | --- |
| Connection settings | Address, password, resource, host/port or DNS SRV, direct TLS, certificate pinning, PLAIN / SCRAM-SHA-1, background mode, log portal | `XmppConfig`, `TlsConfig`, `SaslConfig`, `ReconnectPolicy` |
| Top bar | Live connection state, retry after a fatal error | `XmppClient.state`, `connect()` |
| Chats | Conversations, unread counts, start a chat with any address | `Messaging.events` |
| Chat | Delivery ticks (✓ server, ✓✓ device), typing indicator, offline sending, archive paging | `Messaging.send`, `sendChatState`, `History.fetch` |
| Contacts | Roster with presence, add/remove, approve/deny subscription requests | `Roster`, `Presences` |
| Me | Own presence and status, invisible mode, server capabilities, push registration, disconnect / sign out | `Presences.set/hide`, `Carbons`, `Push`, `CredentialVault` |
| Log | Every stanza in and out (credentials always redacted), connection events, log portal upload stats | `TetherDebug`, `MemorySink`, `RemoteLogSink` |

All SDK calls are in one file, [`TetherSession.kt`](app/src/main/kotlin/io/github/usmandurrany/tether/demo/TetherSession.kt).
The rest is plain Jetpack Compose UI.

## Build and run

You need Android Studio (or JDK 17+ and the Android SDK with API 35) and read access to
`usmandurrany/tether-xmpp`.

```sh
git clone --recursive https://github.com/usmandurrany/tether-demo-android.git
```

Then open the folder in Android Studio and press Run, or from a terminal:

```sh
./gradlew installDebug        # needs a device or emulator; or assembleDebug for the APK
```

Already cloned without `--recursive`? Run `git submodule update --init` first; the build
tells you if the SDK sources are missing.

### How the SDK is included

The SDK is not on Maven Central yet, so it is built from source:

- `tether-xmpp/` is a git submodule pinned to a known-good SDK commit.
- [`settings.gradle.kts`](settings.gradle.kts) includes it as a Gradle composite build
  (`includeBuild("tether-xmpp")`). The app depends on the usual coordinates
  (`io.github.usmandurrany.tether:tether-android`, `tether-im`, `tether-debug`) and Gradle
  substitutes the SDK's modules. There is no publish step, and edits in `tether-xmpp/`
  show up in the app on the next build.
- The SDK only builds its Android module when it finds the Android SDK, so the settings
  script copies this project's `local.properties` into `tether-xmpp/` when that has none.

To move to the latest SDK: `git submodule update --remote tether-xmpp`, then commit the new
pointer. Once the SDK is published to a Maven repository, delete the `includeBuild` lines
and the submodule, and add that repository; the dependency lines stay the same.

## Connecting

Fill in the settings page and press **Connect**:

- **Address**: your full JID, `alice@example.com`.
- **Password**: used for the first login only. Tether keeps the derived SCRAM keys in
  Android Keystore-encrypted storage, so later logins (and **Connect on app start**) need
  no password. **Sign out** on the Me tab deletes them.
- **Host / Port**: leave empty to find the server through the DNS SRV records of the
  domain. Set a host (name or IP) to connect directly, for example to a server on your LAN.
  From the Android emulator, your computer is `10.0.2.2`.
- **Direct TLS**: for servers that speak TLS from the first byte (usually 5223).
  STARTTLS is the default. Tether never connects without TLS.
- **Trusted certificate fingerprints**: for self-signed servers. If the certificate is
  refused, the app shows its SHA-256 fingerprint and offers to trust exactly that
  certificate. Compare it with the server's (`./gradlew :tools:probe:run` in the SDK
  prints it) before accepting.
- **Allow PLAIN / SCRAM-SHA-1**: off by default; turn on only for servers that need them.
- **In the background**: what happens five seconds after the app leaves the screen.
  *Disconnect* lets the server store messages and send push notifications; *Detach* keeps
  the session resumable for a fast return; *Stay connected* keeps the socket open and
  marks the client inactive (CSI).

A quick test: sign in with two accounts on two devices (or a device and any desktop XMPP
client), add each other on the Contacts tab, approve the request, and chat.

Server features show up only when the server offers them (Me tab → *What this server
supports*). History needs `mod_mam` (ejabberd, MongooseIM, Prosody) or Openfire's
Monitoring plugin; without it, chats show messages from this run plus offline messages.

## Push notifications

The Me tab registers any push target by hand (XEP-0357), so you can try push without
setting up Firebase. In your app, the token comes from FCM:

```kotlin
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        appScope.launch {
            im.push.register(PushTarget(
                service = "pubsub.example.com",          // MongooseIM; other servers: your push app server's JID
                node = deviceId,
                publishOptions = mapOf("device_id" to token, "service" to "fcm"),
                createNode = true,
            ))
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        client.wake()   // check the connection now instead of waiting for the next retry
    }
}
```

## Debug logs

The Log tab shows the SDK's traffic, also written to Logcat under the tag `Tether`.
To collect logs from testers, run a [tether-debug](https://github.com/usmandurrany/tether-debug)
portal and fill in its URL and ingest key in the settings. Uploads are batched every two
seconds and flushed when the app goes to the background. Debug builds allow plain `http://`
for a portal on your LAN; release builds need `https://`. Turn on **Hide message text in
logs** for testing rounds with people outside your team.

## Project layout

```
app/src/main/kotlin/io/github/usmandurrany/tether/demo/
  TetherSession.kt        everything that talks to the SDK
  DemoApp.kt              one session per process, sign-in and sign-out
  ConnectionSettings.kt   the settings model and where it is saved
  Conversations.kt        in-memory message list fed by SDK events
  MainActivity.kt         screen switching and theme
  ui/                     Compose screens
```

Messages are kept in memory for the demo; a real app would store them in a database
and use `ChatMessage.serverId` to de-duplicate live and archived copies.

## CI

[`build.yml`](.github/workflows/build.yml) builds the debug APK and uploads it as an
artifact. While `tether-xmpp` is private, add a fine-grained token with *Contents: read* on
that repository as the `TETHER_SDK_TOKEN` secret; without it the build is skipped with a warning.

## License

Apache 2.0, like the SDK.
