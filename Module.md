# Module KMP Connectivity

Internet connectivity monitor for Kotlin Multiplatform: know whether the device can actually reach the internet on
Android, iOS, Desktop (JVM) and Web (JS and Wasm), as a `StateFlow<Boolean>`, and show a "No internet connection"
banner in Jetpack Compose or Compose Multiplatform with one line.

| Module | What it gives you |
|---|---|
| `connectivity-core` | `ConnectivityMonitor`: `isConnected`, `status`, `reconnected`. Coroutines only, no UI. |
| `connectivity-compose` | `ConnectivityStripLayout`: the "No internet connection" / "Back online" strip. |
| `connectivity-ktor` | `ConnectivityHints` Ktor client plugin for faster detection. |
| `connectivity-okhttp` | `ConnectivityHintsInterceptor` for OkHttp on Android and JVM. |

Installation, guides and the full configuration reference are in the
[README on GitHub](https://github.com/lexmerciful/kmp-connectivity-manager).
