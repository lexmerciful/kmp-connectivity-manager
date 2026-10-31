# Module connectivity-okhttp

An OkHttp interceptor for Android and JVM that feeds request outcomes into a
[ConnectivityMonitor][io.github.lexmerciful.connectivity.ConnectivityMonitor] to detect connectivity changes faster.

`OkHttpClient.Builder().addInterceptor(ConnectivityHintsInterceptor(monitor))`: any response marks the device online,
a connection failure triggers an immediate re-check, and cancelled calls are ignored.

# Package io.github.lexmerciful.connectivity.okhttp

The `ConnectivityHintsInterceptor`.
