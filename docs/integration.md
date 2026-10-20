# Integrating Tether

This guide shows how to add Tether to an Android or JVM app. Most apps only need `TetherChat`: you pick features with on/off flags, and the SDK handles message state, read receipts, typing, history, reconnection, the app lifecycle and threading. The lower-level API stays available for apps that want to work with the protocol directly (see [Going deeper](#going-deeper)).

## Modules

| Module | What it gives you |
| --- | --- |
| `tether-core` | `XmppClient`: login, reconnection, stream resumption, outbox, saved state |
| `tether-im` | `TetherChat`, plus the protocol-level features: roster, presence, messages (receipts, read markers, typing, carbons), history (MAM), push |
| `tether-android` | Android wiring: DNS, TLS (Conscrypt), app lifecycle, network changes, encrypted storage. Brings `tether-im` with it. |

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
    // implementation("io.github.usmandurrany.tether:tether-im:0.1.0-SNAPSHOT")    // desktop and server JVMs
}
```

On a desktop or server JVM, use `tether-im` instead of `tether-android`. Add `org.conscrypt:conscrypt-openjdk-uber:2.7.0` as well if you want the stronger `tls-exporter` channel binding on TLS 1.3; without it the JDK's TLS is used with `tls-server-end-point`.

## Quick start

### 1. Create the chat, once per account

Android, in your `Application` or a DI singleton:

```kotlin
val chat = TetherAndroid.chat(
    context,
    BareJid.parse("alice@example.com"),
    password = typedPassword,          // first sign-in only; later runs use the saved login
    features = ChatFeatures(),         // everything on; see "Features" below
)
chat.connect()
```

Desktop or server JVM:

```kotlin
import io.github.usmandurrany.tether.im.chat

val chat = TetherJvm.chat(BareJid.parse("alice@example.com"), password = typedPassword)
chat.connect()
```

That is all the setup. `chat(...)` is a suspend function (it reads the saved login), so call it from a coroutine. Without a password it signs in with the keys the last sign-in saved, and throws `IllegalArgumentException` when there are none: check first with `CredentialVault(TetherAndroid.secureStore(context)).hasKeys(jid)` to decide whether to show a password field.

The chat runs on its own background threads. Every function can be called from the UI thread, and every list is a `StateFlow`, so there is no "collect before connecting" rule: collect whenever the screen needs it. On Android it also follows the app lifecycle and the network (closing the connection in the background so the server sends pushes, reconnecting when the app comes back) and keeps its state in Keystore-encrypted storage. The password itself is never stored.

Use one `TetherChat` per account for the life of the process. Two for the same account would keep kicking each other off the server.

### 2. Show the chat list and a chat

```kotlin
chat.conversations.collect { list -> render(list) }   // most recent first
chat.messages(bob).collect { messages -> render(messages) }  // oldest first
chat.unreadCount.collect { badge(it) }
chat.connection.collect { state -> header(state) }    // Online, Connecting, WaitingForNetwork...
```

Each `Conversation` has the contact's `name`, `lastMessage`, `unreadCount`, `typing` and `online`. Each `Message` has a stable `id` (use it as the list key), `body`, `outgoing`, `timestamp` (epoch milliseconds, the server's time when it kept the message) and `status`:

| Status | Shown as | Meaning |
| --- | --- | --- |
| `SENDING` | no tick | Ours, waiting on this device (also while offline) |
| `SENT` | one tick | The server has it |
| `DELIVERED` | two ticks | The contact's device has it |
| `READ` | blue ticks | Ours: the contact read it. Theirs: we read it, here or on another device |
| `FAILED` | error | The server refused it; `error` says why. `chat.retry(message)` sends it again |
| `UNREAD` | bold | Theirs, not read yet |

The status only moves forward, whatever order the network reports things in, and the same message never shows twice (live, a copy from another of your devices, and the archive are merged).

### 3. Send, type, and tell the SDK what is on screen

```kotlin
chat.send(bob, "Hi Bob")      // returns the Message at once, as SENDING; works offline
chat.typing(bob)              // on every change of the text field
chat.openChat(bob)            // the chat is on screen
chat.closeChat(bob)           // it left the screen
```

`openChat` is how read receipts work: while a chat is open and the app is in the foreground, its messages are marked read and the contact is told (only for messages that asked for it, as XEP-0333 requires). The first time a chat opens, its newest page of history loads. Call `chat.loadOlder(bob)` when the user scrolls to the top; it returns false when there is nothing older.

`typing` sends "typing" once, then "paused" after five idle seconds; sending the message ends it. A contact who stops typing without saying so stops showing as typing after 30 seconds.

### 4. Contacts and presence

```kotlin
chat.contacts.collect { render(it) }               // name, online, show (away, busy), status
chat.contactRequests.collect { askUser(it) }       // people asking to see our presence

chat.addContact(bob, name = "Bob")   // adds and asks to see Bob's presence
chat.approve(carol)                  // lets Carol see ours, and asks for hers
chat.deny(mallory)
chat.removeContact(bob)
chat.setPresence(Show.AWAY, "In a meeting")
```

These need a connection and throw `XmppStanzaException` if the server refuses.

### 5. Sign out

```kotlin
chat.signOut()   // stops push, disconnects, forgets the saved login, contacts and messages
chat.close()     // or: just stop, keeping the login for next time
```

## Features

Every feature is on by default. Switch off what your app does not want:

```kotlin
ChatFeatures(
    deliveryReceipts = true,   // two ticks (XEP-0184)
    readMarkers = true,        // blue ticks: see when contacts read ours (XEP-0333)
    sendReadMarkers = true,    // tell contacts when we read theirs
    typingIndicators = true,   // both ways (XEP-0085)
    contacts = true,           // the contact list on the server (roster)
    presence = true,           // contacts' online status and requests
    multiDevice = true,        // messages from our other devices (carbons, XEP-0280)
    history = true,            // the server archive (XEP-0313)
    lastSeen = true,           // when offline contacts were last online (XEP-0012)
    deleteForEveryone = true,  // delete for everyone, both ways (XEP-0424)
)
```

`sendReadMarkers` is a privacy setting: with it off you still see other people's read markers. It can also change at run time with `chat.sendReadMarkers = false`. With `presence` off the account still goes online (the server only delivers messages to an online device); contacts' status just is not tracked.

Each feature also checks that the server supports it, so a feature the server lacks turns itself off. See the [server checklist](#server-checklist).

## Keeping messages

By default messages live in memory. After a restart, recent chats are rebuilt from the server archive, and each chat's history loads when it opens. To keep messages on the device, implement `MessageStore` (three functions: `load`, `save`, `clear`) on Room, SQLDelight or a file, and pass it:

```kotlin
val chat = TetherAndroid.chat(context, jid, password, messageStore = RoomMessageStore(db))
```

`save` receives every new or changed message; replace by `peer`, `outgoing` and `id`. Keep a message's custom data (below) in one text column with `Payloads.toXml(message.payloads)`, and read it back with `Payloads.fromXml`.

After each new sign-in the SDK also catches up from the archive: messages sent and received on other devices while this one was offline appear, and ones read elsewhere are marked read.

## When a message counts as sent

A message shows SENT (one tick) only once the server has confirmed it (XEP-0198 stream management); until then it is SENDING. Nothing is dropped in between:

- Unconfirmed messages stay queued. A resumed session re-sends what the server says it missed; a new session sends all of them first. The queue survives an app restart.
- Right after each chat message the SDK asks the server to confirm and checks that the connection answers. A connection that has silently died (common after a network switch) is found within 10 seconds (20 in the background), replaced, and the message goes out again. Wake-ups, network changes and coming to the foreground check it too.
- A re-send can reach the contact twice. `TetherChat` merges copies by message id; other apps may show both.

Without stream management the server never confirms anything, so a message counts as sent once it is written. Use a server with XEP-0198 (see the [server checklist](#server-checklist)).

## Custom data in messages

A message can carry your own data beside its text: a photo's link and size, a location, the id of the message it replies to. There is nothing to set up. Give the data a type name and some fields:

```kotlin
chat.send(bob, "Sunset at the beach", MessageData("photo", "url" to url, "size" to 48213, "width" to 1024))
```

Read it on the other side:

```kotlin
val photo = message.data("photo")   // null for a plain text message
if (photo != null) {
    PhotoBubble(url = photo.string("url"), size = photo.long("size"), width = photo.int("width"), caption = message.body)
} else {
    TextBubble(message.body)
}
```

Each field becomes its own tag in the message, next to the text:

```xml
<message to="bob@example.com" type="chat" id="…">
  <body>Sunset at the beach</body>
  <photo xmlns="urn:tether:data:0">
    <url>https://files.example.com/sunset.jpg</url>
    <size>48213</size>
    <width>1024</width>
  </photo>
  …
</message>
```

- **Values** can be strings, numbers or booleans. Read them back with `string`, `int`, `long`, `double` and `boolean`, or `photo["url"]`. Null values are left out. Type names and keys use letters, digits, `-`, `_` and `.`.
- **Several kinds in one message:** `chat.send(bob, "Here", MessageData("photo", …), MessageData("location", …))`. `message.allData()` lists them all.
- **The text is the fallback.** Other XMPP apps show only the text, so make it useful: a caption, or the link itself. It can be empty when the data says it all; the SDK then asks the server to keep the message anyway.
- **Upload first, send the link.** Servers limit message size, often to 64 KB, so put the file on your own storage or the server's HTTP upload service and send its address, never the bytes.
- **It goes everywhere a message goes:** live, copies from your other devices, offline storage, the archive, and `retry`. A malformed field reads as null rather than throwing, so a bad message from someone else can't break a chat.
- **Keeping messages on the device:** a `MessageStore` can keep the data in one text column with `Payloads.toXml(message.payloads)` and `Payloads.fromXml(text)`.

### Your own classes, lists and groups

To map data straight to a class, or to send lists and nested groups, describe it once with `payloadType`:

```kotlin
data class Photo(val url: String, val size: Long, val tags: List<String>, val thumbnail: String?)

val PhotoType = payloadType<Photo>(
    name = "photo",
    write = {
        put("url", it.url)
        put("size", it.size)
        putAll("tag", it.tags)                              // a list: one tag per value
        put("thumbnail") { put("url", it.thumbnail) }       // a group of fields
    },
    read = {
        Photo(
            url = string("url") ?: return@payloadType null,  // null = not usable, skipped
            size = long("size") ?: 0,
            tags = strings("tag"),
            thumbnail = child("thumbnail")?.string("url"),
        )
    },
)

chat.send(bob, "Sunset", PhotoType.write(photo))
val photo: Photo? = message.payload(PhotoType)
```

Data sent this way and as `MessageData` with the same type name share one format, so either side can read the flat fields with `message.data("photo")`.

### Going further

- **Sharing a format with other apps:** pass `namespace = "urn:yourcompany:photo:0"` to `payloadType`. Without it the SDK uses its own namespace, `urn:tether:data:0`, which only matters to apps reading the raw XML.
- **Raw XML:** `message.payloads` holds every element in the message that the SDK doesn't handle itself, including other apps' data such as `jabber:x:oob` links. For a shape fields can't express, implement `PayloadType` and build the `Element` with the `element(...)` builder.
- **What's refused:** namespaces the SDK uses itself (receipts, markers, typing and so on) throw `IllegalArgumentException`, and they never show up in `payloads`.
- **Protocol level:** `im.messaging.send(to, body, payloads = listOf(MessageData(…).toElement()))`, and `ChatMessage.data(type)` or `ChatMessage.payload(type)` on received messages.

## Last seen

`Contact.lastSeen` and `Conversation.lastSeen` hold when an offline contact was last online, in milliseconds since the epoch. They are null while the contact is online, or when the time isn't known. There is nothing to call:

- When a contact goes offline while you are connected, the SDK notes the time.
- When a chat opens (`openChat`) with an offline contact, the SDK asks the server (XEP-0012).

```kotlin
val header = when {
    conversation.online -> "online"
    conversation.lastSeen != null -> "last seen ${formatTime(conversation.lastSeen)}"
    else -> ""
}
```

To ask at another time, call `chat.lastSeen(peer)`. The contact's server only answers people allowed to see their presence, and some servers don't keep it at all, so "unknown" is a normal answer. `ChatFeatures(lastSeen = false)` turns it off.

## Deleting messages

```kotlin
chat.deleteForEveryone(message)   // ours only; returns false for theirs
chat.deleteForMe(message)         // any message, this device only
```

- **Delete for everyone** (XEP-0424) works on your own messages. The message stays in the list as `message.deleted == true`, with no text or data, so show a placeholder such as "This message was deleted". It is deleted on your other devices too. The contact's app removes it if it supports deletion; older apps show a short note instead. A server that supports it may also remove the message from the archive.
- **When a contact deletes theirs,** the same happens here: `message.deleted` turns true, and an unread one stops counting as unread. Only a message's own sender can delete it; a deletion from anyone else is ignored. A deletion that arrives before its message (from the archive, say) still applies when the message comes in.
- **Delete for me** removes the message from this device's lists. It keeps a hidden copy (`message.hidden`), so a copy from the archive can't bring it back. A `MessageStore` should save it like any other message.
- `ChatFeatures(deleteForEveryone = false)` ignores contacts' deletions and makes `deleteForEveryone` return false.

## Push notifications

Get a token from FCM (or APNs), then register it with the server's push service. The fields differ by server:

```kotlin
// MongooseIM (mod_event_pusher_push)
chat.registerPush(PushTarget(
    service = "pubsub.example.com",
    node = deviceId,                                  // any stable id per device
    publishOptions = mapOf("device_id" to fcmToken, "service" to "fcm"),
    createNode = true,
))
```

For ejabberd and Openfire, `service` is the push app server's JID and `node` and `publishOptions` are what it gave this device (see the `PushTarget` docs). Watch `chat.im.push.state`: `Unsupported` means the server has no XEP-0357 support. Tether re-registers on every new login.

When a push arrives, call `chat.client.wake()`: it checks a live connection at once, or skips the wait before the next retry.

## Desktop notes

`TetherJvm.chat` keeps its state in `~/.tether/<account>.properties`, readable by your user only (pass `store = FileKeyValueStore(path)` to choose the place). Tell the chat about window focus with `chat.client.setForeground(focused)`, so read markers only go out while the user can see the chat, and about network changes with `chat.client.networkChanged(available)` if you can detect them. Without these, the app counts as always in the foreground and always online.

## Going deeper

`chat.im` gives the protocol-level features `TetherChat` is built on, and `chat.client` the connection itself. You can also build them yourself, as below, and handle the events directly. Install everything before `connect()`.

### Android

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

### JVM (desktop, server, tools)

```kotlin
val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
val store = FileKeyValueStore(Path.of(System.getProperty("user.home"), ".myapp", "tether.properties"))
val client = TetherJvm.client(XmppConfig(BareJid.parse("alice@example.com"), password = secret), scope, store = store)
val im = InstantMessaging(client)
client.connect()
```

Desktop apps should call `client.setForeground(...)` and `client.networkChanged(...)` themselves if they know about window focus or network changes. Without them the client behaves as a foreground app on an always-available network.

### Connection state

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

### Messages

```kotlin
scope.launch {
    im.messaging.events.collect { event ->
        when (event) {
            is MessageEvent.Received -> save(event.message)       // live, carbons and offline messages
            is MessageEvent.SentToServer -> markSent(event.id)    // the server confirmed it (XEP-0198)
            is MessageEvent.Delivered -> markDelivered(event.id)  // the contact's device confirmed it (XEP-0184)
            is MessageEvent.Displayed -> markReadUpTo(event.peer, event.id)          // the contact read it and all before (XEP-0333)
            is MessageEvent.DisplayedElsewhere -> markTheirsReadUpTo(event.peer, event.id) // we read theirs on another device
            is MessageEvent.Failed -> markFailed(event.id, event.condition)
            is MessageEvent.ChatStateChanged -> showTyping(event.peer, event.state)
        }
    }
}

val id = im.messaging.send(BareJid.parse("bob@example.com"), "Hi Bob")
im.messaging.sendChatState(bob, ChatState.COMPOSING)
```

`send` works while offline: the message waits in the outbox (kept across restarts) and goes out on the next connection. Save the returned id with the message so you can match the later events. `ChatMessage.serverId` is the archive id; use it to de-duplicate messages that arrive both live and from history.

Sent messages are markable (XEP-0333). When a message with `ChatMessage.markable` is on screen, call `im.messaging.markDisplayed(peer, id)` for the newest one; never for messages that are not markable. A marker for an id covers that message and every earlier one. Archive pages carry archived receipts and markers in `HistoryPage.markers`. `InstantMessaging(client, features = ChatFeatures(...))` takes the same feature flags as `TetherChat`.

### Contacts and presence

```kotlin
im.roster.items.collect { contacts -> render(contacts.values) }
im.presences.contacts.collect { online -> renderOnline(online) }
im.presences.subscriptions.collect { if (it is SubscriptionEvent.Requested) askUser(it.from) }

im.roster.set(bob, name = "Bob", groups = setOf("Friends"))
im.presences.request(bob)      // ask to see Bob's presence
im.presences.approve(bob)      // let Bob see ours
im.presences.set(OwnPresence(show = Show.AWAY, status = "In a meeting"))
```

### History

```kotlin
if (im.history.available.value) {
    var page = im.history.fetch(with = bob)                 // newest page
    while (!page.complete) page = im.history.fetch(with = bob, before = page.first)
}
```

History needs `mod_mam` (MongooseIM, ejabberd) or the Openfire Monitoring plugin on the server. `available` is false otherwise.

### Push notifications

`im.push.register(...)` and `im.push.state`, as in [Push notifications](#push-notifications) above.

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
- Last activity (XEP-0012, `mod_last` on ejabberd, `mod_lastactivity` on Prosody), for last seen
- Message retraction (XEP-0424) is optional on the server: clients handle it, and a server that supports it also removes deleted messages from the archive
