# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/). Until 1.0, minor versions may change the public API.

A release is cut by pushing a tag `vX.Y.Z`; the section with the matching version becomes the GitHub Release notes.

## [0.1.0] - Unreleased

First public release.

### Added

- `connectivity-core`: `ConnectivityMonitor` with `isConnected: StateFlow<Boolean>`, `status: StateFlow<ConnectivityStatus>`
  (transport, metered, constrained, disconnect reason) and `reconnected: Flow<Unit>`.
  - Android: `ConnectivityManager` default-network callback; Wi-Fi → cellular handovers never flash offline.
  - iOS: `NWPathMonitor`.
  - Desktop (JVM): interface pre-check plus bounded TCP, HTTP or DNS probes; adaptive, fixed, backoff or custom schedules;
    wake-from-sleep detection.
  - Web (JS and Wasm): `navigator.onLine` events, optional fetch probe that pauses while the tab is hidden.
  - Fail-safe defaults that lean toward "online", validation/failure/reconnect policies, debounce.
  - Callback API for Swift and non-coroutine code: `observe`, `observeStatus`, `observeReconnected`.
- `connectivity-compose`: `ConnectivityStripLayout` and `ConnectivityStrip`, a "No internet connection" strip for Jetpack
  Compose and Compose Multiplatform, with edge-to-edge insets handling, top/bottom placement, push or overlay modes,
  an optional "Back online" confirmation, full styling and a custom slot.
- `connectivity-ktor`: `ConnectivityHints` Ktor client plugin.
- `connectivity-okhttp`: `ConnectivityHintsInterceptor` for OkHttp.

[0.1.0]: https://github.com/lexmerciful/kmp-connectivity-manager/releases/tag/v0.1.0
