# Module connectivity-ktor

A Ktor client plugin that feeds HTTP request outcomes into a
[ConnectivityMonitor][io.github.lexmerciful.connectivity.ConnectivityMonitor] to detect connectivity changes faster.

`install(ConnectivityHints) { monitor = connectivityMonitor }`: any response marks the device online, a connection
failure triggers an immediate re-check (never an instant "offline"), and cancelled requests are ignored.

# Package io.github.lexmerciful.connectivity.ktor

The `ConnectivityHints` plugin and its configuration.
