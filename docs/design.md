# KMP XMPP SDK: Foundation, Authentication and Reconnection Design

Status: proposal v2 (2026-10-19), revised after Usman's review. Development server: **MongooseIM 6.8.0** on Usman's network. Minimum Android: **API 26**. Targets now: **Android** and the **JVM**, which covers every desktop (Linux, Windows, macOS). Native desktop targets (Linux, Windows, macOS) were dropped (2026-10-19): the JVM is enough there. Kept reachable: iOS, Web.

---

## 1. Decision: build our own KMP core, do not use Smack

**Recommendation: build from the ground up in Kotlin Multiplatform `commonMain`, with our own streaming XML parser and writer. Do not adopt Smack, not even behind an adapter.**

| Concern | Smack | Own KMP core |
|---|---|---|
| Platforms | Java only. Works on JVM and Android, can never run on iOS, macOS native, Windows native or Web. | One `commonMain` codebase; only sockets/TLS/DNS/OS hooks are per platform. |
| Lean | Pulls in its own threading, XML pull-parser abstraction, provider registry and a large extension set. | We ship only RFC 6120/6121 plus the XEPs we choose, each as an opt-in module. |
| Concurrency | Thread and listener based. Wrapping it in coroutines means two concurrency models. | Coroutines and `Flow` end to end, one actor per connection. |
| Reconnection control | Its own reconnection manager and SM implementation; tuning it to WhatsApp-grade behaviour means fighting or forking it. | The state machine, keepalive and resumption are ours and designed together. |
| "Decoupled so Smack can be updated" | Possible on JVM via an adapter layer, but the adapter would be a second API surface and would still be dead weight for every non-JVM target. | Not needed. |
| Cost | Fast start on Android. | More upfront work (parser, SASL, SM). Estimated scope is moderate because XMPP uses a restricted XML subset (see 1.1). |

The deciding point: the project goal is a single SDK for six platforms. Smack would give Android and Linux-JVM a head start and then force a second implementation for every other platform, which is the opposite of lean. A Smack adapter that we later replace also means writing the public API twice.

### 1.1 Why a custom parser is realistic and safer

RFC 6120 §11.1 forbids comments, processing instructions, DTDs, internal/external entity references (other than the five predefined ones) and non-UTF-8 encodings in an XMPP stream. That makes the parser small (estimated 1.5 to 2.5k lines) and removes entire attack classes (XXE, billion laughs) by construction rather than by configuration.

Parser requirements:
- **Push / incremental**: fed arbitrary byte chunks from the socket, emits events; never needs the whole document (the stream never ends).
- **Strict UTF-8 validation**, reject overlong sequences and disallowed code points.
- **Namespace aware**, with prefix scoping.
- **Hard limits** (configurable, safe defaults): max stanza size 256 KiB, max depth 32, max attributes per element 64, max attribute value 64 KiB, max namespace declarations per element 16. Exceeding a limit closes the stream with `<policy-violation/>`.
- Any DTD, PI, comment, undeclared entity → `<restricted-xml/>` stream error, disconnect.
- Fuzzed in CI (Jazzer on JVM).

Writer: escapes text and attributes, never emits unescaped user input, builds stanzas from an immutable element tree.

We considered `xmlutil` (pdvrieze, KMP). Its strength is serialization and it reads whole documents through a pull reader; we need a push parser over an endless stream with strict limits, so we would wrap most of it anyway. Not adopted; revisit only if our parser proves costly.

---

## 2. Module layout

All modules are Kotlin Multiplatform; platform code lives in `jvmMain` and `androidMain` (later `iosMain`, `jsMain`/`wasmJsMain`).

```
tether-xml            Streaming parser, writer, immutable Element model. No other deps.
tether-core           Stream negotiation, connection state machine, stanza router,
                    IQ request/response with timeouts, extension SPI, public API.
tether-sasl           SCRAM-SHA-1/256/512 (+PLUS), PLAIN (opt-in), SASL2, FAST.
tether-sm             XEP-0198 Stream Management: acks, resumption, unacked queue.
tether-transport      Transport interface + TCP/TLS (expect/actual) + WebSocket (RFC 7395).
tether-dns            SRV resolution (RFC 6120 §3.2, XEP-0368), pluggable resolver.
tether-platform-android  Connectivity callbacks, FCM push glue, Keystore credential store,
                       foreground-service helper.
tether-platform-linux    Network change detection, libsecret / file credential store.
tether-push           Generic XEP-0357 push with pluggable node provisioning (section 4.7).
tether-xep-*          One module per extension (carbons, MAM, CSI, MUC, ...).
tether-e2ee-*         Reserved for later OMEMO / OpenPGP modules (section 5).
```

Dependencies kept to: `kotlinx-coroutines-core`, `kotlinx-io` (byte buffers), a crypto primitive layer (see 3.5). No logging framework; a `Logger` interface the host app implements.

Extension SPI: an extension registers stream-feature handlers, stanza interceptors (in/out) and element parsers keyed by `(namespace, localName)`. The core knows nothing about specific XEPs beyond what connectivity needs (SASL, Bind, SM, CSI).

Public API is UI-agnostic: `XmppClient` exposes `state: StateFlow<ConnectionState>`, `incoming: SharedFlow<Stanza>`, `suspend fun send(...)`, `suspend fun iq(...)`. Swift/JS exports come later through a thin facade.

---

## 3. Authentication and transport security

### 3.1 Connection establishment

1. **Resolve** `_xmpps-client._tcp.<domain>` (direct TLS, XEP-0368) and `_xmpp-client._tcp.<domain>` (STARTTLS) SRV records. Merge by priority/weight, prefer direct TLS at equal priority. Fallback: `<domain>:5222`. Host apps can pin a host/port explicitly.
2. **Happy Eyeballs (RFC 8305)** across resolved addresses: race IPv6 and IPv4 with a 250 ms stagger, take the first TLS-completed connection. Big win on mobile networks with broken v6.
3. **TLS**: minimum TLS 1.2, prefer 1.3. Certificate validated against the **original XMPP domain** (RFC 6125/7590), never the SRV target name, unless the host app supplies an explicit trust override (for self-hosted test servers, explicit and loud).
4. **STARTTLS is mandatory** when not using direct TLS. If the server does not offer it, abort. There is no plaintext mode in release builds; a `allowInsecureForLocalTesting` flag exists only in a debug artifact.
5. Optional **certificate / SPKI pinning** hook and **DANE** hook (resolver-dependent, later).

Per-platform TLS (expect/actual):
- JVM / Android: `SSLEngine` over NIO (Android uses its Conscrypt provider). Exposes the `tls-exporter` and `tls-server-end-point` channel-binding data.
- iOS (later): Network.framework `NWConnection`.
- Web (later): WebSocket only (`wss://`), browser does TLS; no channel binding available there.

### 3.2 SASL mechanisms and policy

Order of preference (client picks the strongest offered, never falls back silently):
1. `SCRAM-SHA-512-PLUS`, `SCRAM-SHA-256-PLUS` with channel binding `tls-exporter` (RFC 9266) on TLS 1.3, `tls-server-end-point` otherwise. Server's supported types discovered via XEP-0440.
2. `SCRAM-SHA-512`, `SCRAM-SHA-256`.
3. `SCRAM-SHA-1` only if the host app enables a legacy flag.
4. `PLAIN`, available as a configuration option (`auth.allowPlain = true`, off by default). Needed for servers backed by LDAP or external auth, where SCRAM is impossible. Rules when enabled:
   - only after TLS is established and the certificate verified; never on an unverified or plaintext stream;
   - still lower priority than any SCRAM mechanism the server offers, unless the app sets `auth.mechanismOrder` explicitly (e.g. to force PLAIN for an LDAP server that also advertises a broken SCRAM);
   - because PLAIN cannot use derived keys, the SDK asks for a FAST token right after the first PLAIN login, so later reconnects do not need the password. If FAST is unavailable, the app either supplies the password on each reconnect through a `PasswordProvider` callback or explicitly opts in to storing it encrypted in the `CredentialStore`.

Downgrade protection: implement **XEP-0474 (SASL SCRAM Downgrade Protection)**, so a man in the middle cannot strip `-PLUS` mechanisms or channel-binding types. If the server previously advertised `-PLUS` for this account (remembered per account) and now does not, fail with a security error instead of continuing.

All SCRAM comparisons (server signature) are constant time. Nonces come from the platform CSPRNG, 24+ bytes.

### 3.3 SASL2 + Bind2 + FAST: fast and safe re-login

All three are used only when the server advertises them (see section 3.5). Use **XEP-0388 (SASL2)** when offered, with **XEP-0386 (Bind2)** inline, so authentication, resource binding, carbons, CSI and SM enable all happen in one round trip. Fall back to classic SASL + RFC 6120 bind when SASL2 is absent.

Use **XEP-0484 (FAST)** to get a server-issued token after the first full login. Reconnects then authenticate with the token (`HT-SHA-256-ENDP` / `-EXPR` bound to the TLS channel), so:
- the password is needed only for the first login and for token renewal failures;
- reconnects need 1 round trip, with TLS 1.3 0-RTT considered later (replay-safe only for the token mechanism);
- a leaked token is revocable server-side without changing the password.

### 3.4 Credential storage

The SDK never persists the plaintext password. It stores, behind a `CredentialStore` interface:
- SCRAM `SaltedPassword`-derived `ClientKey`/`ServerKey` + salt + iteration count (lets us re-auth with SCRAM without the password, and rejects a server that suddenly changes salt/iterations unless the user re-enters the password);
- the FAST token and its expiry;
- the SM resumption id (non-secret but account-scoped).

Implementations: Android → AES-GCM key in Android Keystore (StrongBox when present) encrypting a file in no-backup storage. Linux → libsecret (Secret Service) when available, else an encrypted file with a key provided by the host app. Host apps may plug in their own store.

### 3.5 Capability gating: nothing is enabled without checking the server first

Rule: **the SDK never sends a feature-specific request the server has not advertised.** Every feature has a declared capability requirement, checked before use.

Sources of truth, gathered on every new session (not reused from a previous session unless the stream features hash is identical):
- **Stream features** (pre- and post-auth): STARTTLS, SASL mechanisms, SASL2 + its inline features (Bind2, SM, FAST), channel-binding types (XEP-0440), SM (XEP-0198), CSI (XEP-0352), roster versioning.
- **disco#info on the server domain**: Carbons, blocking, HTTP upload, ping, and so on.
- **disco#info on the account's bare JID**: MAM (`urn:xmpp:mam:2`), push (`urn:xmpp:push:0`), PEP.
- **disco#items / disco#info on components**: PubSub service and push app servers.

These form an immutable `ServerCapabilities` object exposed as `StateFlow` to the app. Each extension module declares `requires: Set<Feature>`; the core activates it only when satisfied and otherwise reports `FeatureState.Unsupported(feature)` instead of failing silently or sending blind requests. The SM resume path re-validates the cached capabilities by comparing the stream features; any difference triggers a fresh discovery.

On MongooseIM 6.8.0 the first Phase 1 task is a capability probe that logs all of the above, so the design is checked against what the real server offers before features are built on assumptions.

### 3.6 Crypto primitives

SHA-1/256/512, HMAC, PBKDF2 (Hi), CSPRNG behind an expect/actual `Crypto` object: JCA on JVM/Android, CommonCrypto on iOS, WebCrypto on JS. No third-party crypto library in common code.

---

## 4. Reconnection and stability ("WhatsApp-grade")

The goal is: the user never loses a message, rarely notices a drop, and the battery is not drained. That comes from five pieces working together.

### 4.1 Connection state machine

```
Idle → Resolving → Connecting → Securing → Authenticating → Binding/Resuming → Online
                                                                         ↓
             Backoff ← Disconnected(reason) ← ─ ─ ─ any failure ─ ─ ─ ─ ┘
                 ↓
              Resolving (retry)        AuthFailed / Conflict / PolicyViolation → Terminal (no auto-retry)
```

- Runs as a single coroutine actor per account: no shared mutable state, every event (socket bytes, timer, network change, API call) goes through one channel, so races between "network changed" and "reconnect timer fired" cannot happen.
- Errors are classified: **transient** (I/O, timeout, `system-shutdown`, `connection-timeout`) → retry; **terminal** (`not-authorized`, `account-disabled`, `conflict` with resource takeover, cert failure) → stop and surface to the app. Certificate failures are never retried automatically.

### 4.1a Single-connection guarantee (no duplicate connects, no accidental teardown)

Mobile platforms fire many overlapping triggers: bursts of network callbacks, push wake-ups, foreground transitions, reconnect timers, the app calling `connect()`. The design makes it impossible for these to create two sessions or kill a healthy one.

1. **One supervisor per account per process.** `XmppClient` instances come from a registry keyed by account (bare JID). Asking for the same account twice returns the existing supervisor. All connection work for that account happens inside its single actor coroutine; nothing else can open a socket.
2. **Triggers are intents, not commands.** Network change, push wake, foreground, timer and API calls are all sent to the actor as intents. The actor decides based on the current state:

   | Current state | `connect`/push wake/foreground | network change | timer fired |
   |---|---|---|---|
   | Online | run a liveness probe, never reconnect directly | probe; reconnect only if the probe fails or the socket's network is gone | ignored (stale) |
   | Connecting/Authenticating/Resuming | join the in-flight attempt (callers await the same result) | restart only if the attempt's network is gone | ignored (stale) |
   | Backoff | cancel the timer, connect now | cancel the timer, connect now | connect |
   | Disconnected (no network) | wait for network | connect if a validated network appeared | ignored |
   | Terminal (auth failure, conflict) | only an explicit `connect()` with new credentials restarts | ignored | ignored |

3. **Epoch tokens.** Each connection attempt gets an increasing epoch number. Every socket event, timer and probe result carries its epoch, and the actor drops anything from an older epoch. A late "socket closed" from a connection we already replaced can therefore never tear down the current one.
4. **Probe before teardown.** No trigger is allowed to close an Online connection on suspicion alone. A failed SM `<r/>` probe (with timeout), a confirmed write error or the OS reporting the socket's network as lost are the only reasons to close.
5. **Make-before-break with a single candidate.** During a network switch there is at most one current connection and at most one candidate. The candidate is promoted only after its SM resume succeeds; then the old socket is closed. If the candidate fails, the current connection is untouched.
6. **Debounced OS signals.** Android delivers `onAvailable`, `onCapabilitiesChanged` and `onLinkPropertiesChanged` in bursts. They are coalesced over a short window (default 500 ms), and only a change in the *validated default network* counts as a network change.
7. **Cross-process lock.** An Android app with several processes (for example a separate push process), or two copies of a Linux app, take an exclusive per-account file lock (`FileChannel.lock`) before connecting. The loser routes through the owner or stays passive, so two processes can never log in the same account at once.
8. **Server-side backstop.** The SDK uses a stable per-installation resource (and Bind2 `<tag>` when available), so a fresh session replaces the old one instead of adding a second. A `<conflict/>` stream error is treated as terminal and is never auto-retried, which prevents two instances from kicking each other in a loop.
9. **Verified by tests, not by review alone.** A property-based test drives the actor with thousands of random interleavings of triggers under virtual time and asserts the invariants: at most one authenticated socket, at most one candidate, no Online connection closed without a failed probe or lost network, and every caller of `connect()` gets a result.

### 4.2 Stream Management (XEP-0198) with resumption

- Enabled on every session with `resume='true'`; the server's `max` resume window is stored.
- **Every outgoing stanza** goes into an unacked queue with a sequence number until the server's `<a h=…/>` covers it. Queue is persisted (host-provided store) so a process death on Android does not lose sent-but-unacked messages.
- Request acks (`<r/>`) after N stanzas or T seconds of unacked data, and always before going to background.
- On reconnect within the window: resume (inline in SASL2 when available), replay stanzas the server did not ack, server replays what we missed. No re-login, no roster fetch, no presence storm.
- If resumption fails: fall back to a fresh session, re-send unacked stanzas (dedup is safe because every stanza carries a stable `id` and XEP-0359 `origin-id`), then **catch up via MAM (XEP-0313)** from the last archived id we saw, and enable **Carbons (XEP-0280)** in the Bind2 request.

### 4.3 Dead-connection detection and adaptive keepalive

TCP alone can take many minutes to notice a dead mobile link. We detect it actively:
- **Idle probe**: when nothing was received for `keepaliveInterval`, send an SM `<r/>` (or XEP-0199 ping if SM is off). No response within `probeTimeout` (default 10 s foreground, 20 s background) → treat as dead, close and reconnect immediately (no backoff on the first attempt).
- **Adaptive interval** (the core of WhatsApp-style stability): start at 60 s, grow by steps toward 270 s while probes keep succeeding, shrink back by one step when a connection dies during an idle period. The learned value is stored **per network** (Wi-Fi SSID hash / cellular carrier id) because NAT timeouts differ per network. This keeps the socket alive through carrier NATs with the fewest wake-ups.
- Whitespace keepalive (single space) is used only as a cheap fallback where SM/ping is unavailable.

### 4.4 Reconnect timing

- First retry immediate. Then exponential backoff with **full jitter**: `delay = random(0, min(cap, base·2^n))`, base 1 s, cap 2 min foreground / 10 min background. Jitter avoids thundering-herd reconnects after a server restart.
- **Backoff resets and an immediate attempt fires** on: network becoming available/validated, network switch (Wi-Fi ↔ cellular), app returning to foreground, explicit `connect()`.
- **No attempts while the OS reports no network**; we wait for the callback instead of burning battery.
- Server-provided hints honoured: `see-other-host`, SM `location`, and stream error `<reset/>`.
- On network switch with a still-alive old socket, we start the new connection first and resume the SM session on it ("make before break"), then drop the old socket.

### 4.5 Platform constraints

**Android**
- Background execution limits and Doze mean a socket cannot be kept alive indefinitely from the background. Strategy:
  1. Foreground: persistent connection, adaptive keepalive.
  2. App backgrounded: send **CSI inactive (XEP-0352)** so the server batches/filters presence and chat states; keep the socket while the process lives; SM acks before suspension.
  3. Doze / process killed: rely on **XEP-0357 push** (server → app server → FCM high-priority message) to wake the app, which reconnects and resumes or MAM-syncs. `handlePushWakeup()` goes through the same actor as every other trigger (section 4.1a), so a push arriving while a connection is alive or in progress does not start a second one. Server differences are handled in section 4.7.
  4. Optional foreground-service mode (Android 14+ type `remoteMessaging`/`dataSync` as appropriate) for apps that need always-on, with a helper but no service forced on the host app.
- `ConnectivityManager.NetworkCallback` drives reconnects; socket bound to the active `Network` so traffic does not leak onto a dying interface.
- `AlarmManager`-based keepalive is avoided (inexact in Doze); keepalive runs only while the process is active, push covers the rest.

**Desktop JVM (Linux, Windows)**
- No background killing, so persistent connection is the norm.
- Network changes via NetworkManager D-Bus signals when present, else periodic interface polling.
- System sleep/resume detected (logind `PrepareForSleep` signal when available, else a monotonic vs. wall-clock jump detector) → immediate liveness probe on wake.

### 4.6 Outgoing message guarantees

- `send()` returns once the stanza is queued; a separate `Flow<DeliveryState>` per message reports `Queued → Sent → AckedByServer → (Receipt via XEP-0184 if enabled)`.
- Messages sent while offline are queued (persisted) and flushed in order after reconnect.
- At-least-once to the server; duplicates suppressed on the receiving side by `origin-id`/`stanza-id`.

### 4.7 Push notifications across servers

Every server implements the XMPP side the same way: XEP-0357 `<enable xmlns='urn:xmpp:push:0' jid='…' node='…'>` sent to the account, with an optional `pubsub#publish-options` data form. What differs is **how the client gets a node** and **which form fields the app server expects**:

| Server / app server | How the node is obtained | Publish-options fields |
|---|---|---|
| MongooseIM (`mod_event_pusher_push` + MongoosePush) | Client creates a PubSub node with `<create node='…' type='push'/>` on the server's PubSub service | `service` (`fcm`/`apns`), `device_id` (token) required; `mode` (`prod`/`dev`), `silent`, `topic`, `click_action` optional |
| Openfire (Push Server plugin) | Ad-hoc command `register-push-fcm` / `register-push-apns` on the push component with `token` and `device-id`; returns `node` + `secret` | `secret`; `sandbox` for iOS dev |
| ejabberd (`mod_push`) and Prosody (`mod_cloud_notify`) | Server only forwards; node comes from an external app server, commonly registered with an ad-hoc command the same way as Openfire (for example the Conversations-style `p2` app server) | Whatever that app server requires, usually `secret` |

The generic implementation splits push into three pluggable parts:
- `PushTokenSource`: gets the platform token (FCM on Android; APNs later).
- `PushNodeProvisioner`: strategy that turns a token into `(jid, node, publishOptions)`. Built in: `PubSubCreateNode` (MongooseIM), `AdHocCommandRegistration` (Openfire and p2-style app servers, command name and field names configurable), `Static` (node already provisioned out of band, for custom backends). Selected automatically from discovery when possible (push identity `pubsub/push` on a component, ad-hoc command list), overridable in configuration.
- `PushEnabler`: sends `<enable>`/`<disable>`, only when `urn:xmpp:push:0` is advertised on the account (section 3.5), re-enables after a fresh (non-resumed) session and whenever the token rotates.

Privacy default: request summary-only/silent pushes without message bodies. This matters even more once end-to-end encryption arrives (section 5).

---

## 5. End-to-end encryption: planned now, built later

E2EE (OMEMO, XEP-0384, as the primary target; OpenPGP for XMPP, XEP-0373, and MLS as possible later options) is out of scope for the first builds. The following decisions are made now so it can be added as a module without reworking the core:

1. **Ordered stanza pipeline.** Outgoing and incoming stanzas pass through ordered interceptor stages (`Prepare → Encrypt → Queue/SM → Wire`, and the reverse). E2EE plugs into the `Encrypt`/`Decrypt` stage. Because encryption sits *before* the SM queue, the persisted unacked queue only ever holds encrypted stanzas; plaintext is never written to disk by the SDK.
2. **Envelope vs. content.** The message model separates routing data (to, from, id, type) from content elements (body, attachments, reactions, and so on). Content can then be wrapped as a single encrypted payload (Stanza Content Encryption, XEP-0420) without each feature knowing about encryption.
3. **PEP/PubSub in the core early.** OMEMO publishes device lists and key bundles through PEP (XEP-0163). Push on MongooseIM already needs PubSub, so this is built in Phase 3 anyway.
4. **Stable device identity.** Each installation keeps a stable device id in the `CredentialStore`, alongside the resource. OMEMO will reuse it.
5. **Key storage interface reserved.** `CredentialStore` gains a namespaced secure-blob API from day one, which OMEMO identity keys, sessions and pre-keys will use.
6. **Crypto layer extensible.** `Crypto` expect/actual starts with hashes/HMAC/PBKDF2 and is designed to add X25519, Ed25519, AES-GCM and HKDF. Note that Android API 26 does not have X25519/Ed25519 in the platform, so OMEMO will need a bundled library on Android (to be chosen then, for example libsignal or a libsodium binding).
7. **Delivery states include crypto outcomes.** `DeliveryState` and incoming events already include `Undecryptable` and `UntrustedDevice` slots so apps built now will not break when E2EE lands.
8. **History goes through the same pipeline.** MAM results and carbons are decoded through the incoming pipeline, so encrypted history decrypts the same way as live traffic.

---

## 6. Testing strategy

- **Unit**: parser (incl. fuzzing), SCRAM test vectors from RFC 5802/7677, SM counter wraparound (h is mod 2^32), state machine transitions with virtual time (`kotlinx-coroutines-test`).
- **Integration on Linux**: primary target is Usman's **MongooseIM 6.8.0**. In CI, the same MongooseIM version runs in Docker so tests are reproducible, with Prosody and ejabberd added later as compatibility targets.
- **Chaos**: Toxiproxy between client and server to inject latency, packet stalls, half-open connections, resets and bandwidth limits. Scenario suite: "kill link mid-stanza", "drop acks", "server restart", "NAT timeout at 90 s", "flap network every 5 s for a minute". Pass criteria: zero lost messages, zero duplicates surfaced to the app.
- **Android**: emulator network toggling and `adb shell dumpsys deviceidle force-idle` for Doze; a small sample app for manual soak tests on a real device.

---

## 7. Roadmap and review process

| Phase | Deliverable |
|---|---|
| 0 | Gradle KMP skeleton (JVM and Android targets declared), CI, lint, `tether-xml` parser + fuzzing. |
| 1 | `tether-transport` TCP/TLS on JVM/Android, `tether-dns`, `tether-sasl` (SCRAM + PLUS + downgrade protection + optional PLAIN), classic bind, capability discovery. First deliverable: a capability probe run against MongooseIM 6.8.0. |
| 2 | State machine with the single-connection guarantee, SM with resumption, adaptive keepalive, backoff, network monitors (Android + Linux JVM). Property-based and chaos test suites green. |
| 3 | SASL2 + Bind2 + FAST (if MongooseIM advertises them), credential stores, CSI, Carbons, PEP/PubSub, MAM catch-up, generic push + FCM module. |
| 4 | iOS, WebSocket transport for Web. (Native desktop targets dropped: the JVM covers Linux, Windows and macOS.) |

Review process: code arrives as small pull requests, one logical piece each (for example "XML parser", "SCRAM", "connection actor"). Each PR description explains the logic, the invariants it relies on and how it is tested. Nothing is merged without Usman's review.

## 8. Decisions log

- Own KMP core, no Smack (v1).
- PLAIN available as an opt-in configuration option (v2).
- Every feature gated on advertised server capabilities (v2).
- Development server MongooseIM 6.8.0; minimum Android API 26 (v2).
- Push generic over MongooseIM, Openfire, ejabberd and Prosody through pluggable node provisioning (v2).
- E2EE deferred, with extension points fixed now (v2).
- A high-level `TetherChat` API is the documented path: features are switched with `ChatFeatures` flags, and the SDK owns message state, read markers (XEP-0333), typing, history catch-up, lifecycle and threading. The protocol-level extensions stay public for advanced use (2026-10-19).
- Apps add their own data to messages as child elements beside the body. `MessageData(type, fields)` needs no setup and uses the SDK's namespace (`urn:tether:data:0`); `payloadType` maps to app classes with lists and groups, with an optional namespace of the app's own. Other clients ignore what they don't know, and the body stays the fallback text (2026-10-20).
- Last seen uses XEP-0012 queried on `openChat`, plus the time a watched contact goes offline; delete for everyone is XEP-0424 v1, honoured only from the message's own sender, with "delete for me" kept as a hidden tombstone so the archive cannot restore it (2026-10-20).
- Profiles use vcard-temp (XEP-0054) with avatar hashes in presence (XEP-0153), the pair every server and most clients support; PEP avatars (XEP-0084) and vCard4 can come later. Our vCard is read during session start, before presence goes out, so the first presence already carries our hash; contacts' profiles are fetched one at a time when their advertised hash changes or a chat opens. Pictures stay in memory: the key-value stores are small encrypted or properties files, not a place for images (2026-10-20).
- Integrator surface trimmed so apps rarely need `chat.im`: `setPresence(invisible = true)`, `addContact(groups)` (null keeps existing groups, fixing renames that dropped them), `pushState`, `ConnectResult.Failed.untrustedCertificate`, `rememberLogin` on the factories, and the JVM network monitor moved from the desktop demo into `TetherJvm.client` so desktop reconnects on network changes without app code (2026-10-20).
- Push app servers are provisioned with their XEP-0050 ad-hoc command (`PushAppServer`, presets for p2, Openfire and Tigase), answering a blank form in the same command session when the server asks for one; every non-node field of the answer becomes a publish option. A push wake (`wakeForPush`) is an explicit connect that, in the background, closes cleanly once the stream is quiet (`pushQuietMillis`) or at the latest `pushWakeMillis` after the first push of the wake (the OS's network window counts from the push), does not retry a connection lost after coming online, and a connect the background closes before it comes up returns `Cancelled` instead of waiting for the foreground (2026-10-20).
- Every chat message triggers an immediate SM ack request and liveness probe (`ClientPolicy.confirmEachMessage`), so a zombie connection is replaced within the probe timeout instead of the idle keepalive interval (2026-10-20).
- Messages persist by default: `SqliteMessageStore` on Android, `FileMessageStore` (append-only, compacted) on the JVM, each storing the whole message through `MessageCodec` so new fields need no migration. Apps swap in their own `MessageStore` and verify it with `MessageStoreCheck` (2026-10-20).
- Paged message stores: `MessageStore.paged`, `chats()` and `loadBefore(peer, MessagePosition, limit)`. With a paged store (both built-in ones are), `TetherChat` keeps each chat's newest 100 messages plus unread in memory, `loadOlder` reads the store before the archive, and `closeChat` trims back to 100. Merging and change tracking are linear, so a chat of 20,000 messages updates in milliseconds (2026-10-21).

Sources for push behaviour: [Tigase Push component](https://docs.tigase.net/tigase-push/1.0.0/Tigase_Push_Notifications_Guide/html/), [MongooseIM push guide](https://mongooseim-global-distrib.readthedocs.io/en/latest/user-guide/Push-notifications/), [Openfire Push Server plugin](https://igniterealtime.org/projects/openfire/plugins/1.1.0/pushserver/readme.html), [XEP-0357](https://www.xmpp.org/extensions/xep-0357.html).
