# Debug tools: stanza logging and remote logs

`tether-debug` shows the XML a client sends and receives, adds the app's own
events to the same log, and can upload everything close to real time to a
self-hosted log portal ([tether-debug](https://github.com/usmandurrany/tether-debug))
during a testing round.

It is a separate module that plugs into the core through the existing
`TrafficLogger` hook, so apps that don't add it carry none of its code.

## Seeing stanzas locally

```kotlin
val debug = TetherDebug(sinks = listOf(ConsoleSink(LogFormatter(pretty = true))))
val client = TetherJvm.client(config, scope, logger = debug.trafficLogger())   // or TetherAndroid.client(...)
debug.trackConnection(client.state, scope)
debug.log(LogLevel.INFO, "app", "User opened chat with bob")
```

```
2026-10-19T15:30:01.200Z D stanza alice@example.com/phone → <message to='bob@example.com' type='chat' id='…'><body>hi</body></message>
2026-10-19T15:30:01.420Z D stanza alice@example.com/phone ← <a xmlns='urn:xmpp:sm:3' h='12'/>
2026-10-19T15:30:05.002Z I connection: Backoff(attempt=1, delayMillis=0, lastError=…)
```

Sinks:

| Sink | Use |
|---|---|
| `ConsoleSink` | Standard output (desktop, tests). Takes any `(String) -> Unit`. |
| `MemorySink` | The last N entries as a `StateFlow<List<LogEntry>>`, for an in-app debug screen. |
| `RemoteLogSink` | Uploads to a log portal (below). |
| your own | `LogSink { … }`. Logcat: `LogSink { Log.d("Tether", LogFormatter().format(it)) }`. |

A sink's `write` runs on the connection's thread and must not block.

`DebugConfig` options: `stanzas` (on/off), `nonzas` (hide stream features,
TLS, SASL and SM acks), `redactBodies`, an extra app `redactor`,
`maxTextChars` (shortens base64 avatars and the like, default 4096), and
`minLevel`. `TetherDebug.enabled` switches everything off at run time.

The account on each entry is learned from the stream (bare JID from our
stream header, full JID after bind) unless the app passes one to
`trafficLogger(account)`.

## What is never logged

Every stanza is parsed again and passed through `Redaction.credentials`
before any sink sees it, on top of the core's own SASL blanking:

- SASL and SASL2 payloads (`<auth>`, `<challenge>`, `<response>`, `<success>`, `<initial-response>`, `<additional-data>`);
- FAST tokens (XEP-0484), as the `token` attribute;
- passwords in registration and password change (XEP-0077) and legacy auth (XEP-0078);
- data-form fields whose `var` contains pass, secret, token or key (XEP-0357 push secrets);
- `Authorization` and `Cookie` headers in HTTP upload slots (XEP-0363).

Text the redactor cannot parse is replaced by `[unparsed XML, N chars]`
rather than logged. If an app redactor throws, only the element name, `type`
and `id` are kept. With `redactBodies = true`, `<body>`, `<subject>` and
XHTML-IM are replaced by their length, also inside carbons and archive results.

Message bodies are **kept** by default because seeing them is usually the
point while debugging. Turn on `redactBodies` for testing rounds with people
outside the team.

## Remote logging

Off unless the app creates a `RemoteLogSink`. Meant for test builds.

```kotlin
val remote = RemoteLogSink.http(                       // JVM and Android; elsewhere pass your own LogUploader
    RemoteLogConfig(
        endpoint = "https://logs.example.com",
        ingestKey = BuildConfig.TETHER_LOG_KEY,
        device = DeviceInfo.current(installId = storedInstallId, appId = "com.example.chat", appVersion = "1.4.0"),
        label = "beta-3",                              // the testing round, filterable in the portal
    ),
    appScope,
)
debug.addSink(remote)
// when the app goes to the background:
remote.flush()
```

How it behaves:

- **Near real time**: entries gather for `flushIntervalMillis` (2 s) and go up as one gzip-compressed POST, at most `maxBatchEntries` (200) or `maxBatchBytes` (512 KB) per request.
- **Never blocks the app**: `write` puts the entry in a bounded queue (`maxQueuedEntries`, 5000). When the portal is unreachable and the queue fills, the oldest entries are dropped and the count is sent with the next upload, so the portal shows the gap.
- **No loss on retry, no duplicates**: a batch leaves the queue only when the portal accepts it. Retries back off with jitter up to `maxRetryDelayMillis` (60 s). Each entry carries the run's session id and a sequence number, so the portal stores a re-sent batch once.
- **Memory only**: queued entries are lost if the process dies. `flush()` before backgrounding keeps that window small.
- `stats` (`StateFlow<RemoteLogStats>`) shows queued, uploaded and dropped counts and the last error, for a debug screen.
- `installId` should be a random id the app generates once and stores, so the portal can follow one device across runs. Don't use a hardware id.

On Android, cleartext `http://` is blocked from API 28 unless the app's
network security config allows the portal's host; use https in testing
rounds outside your LAN.

## Upload protocol (v1)

This is the contract the portal implements.

```
POST {endpoint}/api/v1/ingest
Authorization: Bearer {ingestKey}
Content-Type: application/json; charset=utf-8
Content-Encoding: gzip            (optional; HttpLogUploader always sends it)
```

```json
{
  "v": 1,
  "device": {"installId": "…", "platform": "android", "osVersion": "14 (API 34)",
             "model": "Google Pixel 8", "appId": "com.example.chat", "appVersion": "1.4.0"},
  "session": {"id": "32 hex chars, one per app run", "startedAt": 1760887801123, "label": "beta-3"},
  "dropped": 0,
  "entries": [
    {"seq": 1, "ts": 1760887801200, "level": "debug", "tag": "stanza",
     "account": "alice@example.com/phone", "dir": "out",
     "text": "<message to='bob@example.com' type='chat' id='…'><body>hi</body></message>",
     "stanza": {"name": "message", "type": "chat", "id": "…", "to": "bob@example.com"}},
    {"seq": 2, "ts": 1760887802000, "level": "info", "tag": "connection", "text": "Online(jid=…)"}
  ]
}
```

- Optional fields are omitted, never `null`: `device.osVersion/model/appId/appVersion`, `session.label`, `entry.account/dir/stanza/error`, and each field of `stanza`.
- `level`: `verbose`, `debug`, `info`, `warn`, `error`. `dir`: `out` (sent) or `in` (received), only on stanza entries. `ts` and `startedAt` are Unix milliseconds from the device clock.
- `stanza.payload` is the namespace of the first child, e.g. `jabber:iq:roster`. `stanza.name` is `stream` for stream headers.
- `dropped` counts entries lost since the previous accepted upload of this session.
- `(session.id, seq)` is unique; the portal must ignore an entry it already has.

Responses the SDK acts on:

| Status | SDK does |
|---|---|
| 2xx | Batch accepted. |
| 401, 403 | Wrong key: retries every `maxRetryDelayMillis`. |
| 408, 429, 5xx, network error | Retries the same batch with backoff. |
| 413 | Splits the batch in two and sends each half; a single entry that is too large is dropped. |
| other 4xx | Drops the batch and counts it as dropped. |

The ingest key ships inside the app, so it only keeps strangers out; it is
not a secret. Rotate it per testing round. Viewing logs in the portal needs
separate admin credentials.
