# Tether

A lean, modular XMPP SDK built with Kotlin Multiplatform. UI-agnostic, with an extensible API, designed for connection stability on mobile and desktop.

First targets: **Android (API 26+)** and **Linux (JVM, then Kotlin/Native)**. iOS, macOS, Windows and Web are planned.

Status: early development. See [docs/design.md](docs/design.md) for the architecture and roadmap.

## Probing a server

`tools/probe` connects to any XMPP server, logs in, and prints what the server advertises: stream features at each stage, SASL mechanisms, channel-binding types, and disco#info for the server and your account. It only uses standard RFC 6120 and XEP-0030 discovery, so it works on any compliant server.

```sh
cp xmpp-test.properties.example xmpp-test.properties   # git-ignored; fill in your account
./gradlew :tools:probe:run                              # add --args="--verbose" to see the XML (SASL redacted)
```

If the server uses a self-signed certificate, the probe prints its SHA-256 fingerprint and the `tls.pin=` line to add. Requires JDK 17 or newer.

## License

Licensed under the [Apache License, Version 2.0](LICENSE). You may use, modify and redistribute it freely; redistributions must keep the copyright and the [NOTICE](NOTICE) file crediting the original author.
