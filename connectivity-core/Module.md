# Module connectivity-core

Kotlin Multiplatform internet connectivity monitor for Android, iOS, Desktop (JVM) and Web (JS and Wasm).

[ConnectivityMonitor][io.github.lexmerciful.connectivity.ConnectivityMonitor] exposes `isConnected: StateFlow<Boolean>`,
a richer `status: StateFlow<ConnectivityStatus>` (transport, metered, constrained, disconnect reason) and a
`reconnected: Flow<Unit>` event for auto-retry. It uses the platform's own signal: Android `ConnectivityManager`
default-network callbacks, iOS `NWPathMonitor`, browser `navigator.onLine` events, and bounded TCP/HTTP/DNS
probes on desktop. Every default leans toward "online", so users never see a false "No internet" alert.

Create one monitor per process with the platform factory: `ConnectivityMonitor(context)` on Android,
`ConnectivityMonitor()` on iOS and Web, `ConnectivityMonitor { desktop { tcp { targets = ... } } }` on desktop.

# Package io.github.lexmerciful.connectivity

The monitor, its configuration ([ConnectivityConfig][io.github.lexmerciful.connectivity.ConnectivityConfig]),
the status model and the policies.

# Package io.github.lexmerciful.connectivity.config

Platform-specific options: desktop probe strategies and targets, check schedules, and the optional web probe.
