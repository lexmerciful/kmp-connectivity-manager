# KMP Connectivity

A Kotlin Multiplatform library that tells your app whether the device can reach the internet, and optionally shows a "No internet connection" strip in Compose. Android, iOS, Desktop (JVM), Web (JS + Wasm).

| Artifact | What it gives you |
|---|---|
| `connectivity-core` | `ConnectivityMonitor`: `isConnected`, `status`, `reconnected`. Coroutines only, no UI. |
| `connectivity-compose` | `ConnectivityStripLayout` + `ConnectivityStrip` (Material 3 defaults, every visual overridable, fully custom slot). Works in Jetpack Compose and Compose Multiplatform. |
| `connectivity-ktor` | `ConnectivityHints` Ktor client plugin: request outcomes speed up detection. |
| `connectivity-okhttp` | `ConnectivityHintsInterceptor` for OkHttp (Android / JVM). |

```kotlin
dependencies {
    implementation("io.github.lexmerciful:connectivity-core:<version>")
    implementation("io.github.lexmerciful:connectivity-compose:<version>") // optional UI
    implementation("io.github.lexmerciful:connectivity-ktor:<version>")    // optional
}
```

## Quick start

Create **one** monitor per process (DI singleton), with the platform factory:

```kotlin
// Android (only applicationContext is kept)
val monitor = ConnectivityMonitor(context)

// iOS, Web
val monitor = ConnectivityMonitor()

// Desktop: probe targets are required, the library never contacts a third-party host on its own
val monitor = ConnectivityMonitor {
    desktop { tcp { targets = ProbeTargets.PublicResolvers + ProbeTargets.fromUrl("https://api.example.com") } }
}
```

Shared code can build the configuration once and hand it to each platform factory:

```kotlin
val config = ConnectivityConfig {
    debounce { offline = 1.seconds }
    desktop { tcp { targets = ProbeTargets.PublicResolvers } }
}
val monitor = ConnectivityMonitor(config)          // ConnectivityMonitor(context, config) on Android
```

Read it anywhere:

```kotlin
monitor.isConnected   // StateFlow<Boolean>
monitor.status        // StateFlow<ConnectivityStatus>: Connected(transport, isMetered, ...), Disconnected(reason), ...
monitor.reconnected   // Flow<Unit>: one event per offline → online, for your own auto-retry
```

From Swift (no coroutines needed): `let handle = monitor.observe { connected in ... }`, and `handle.close()` to stop.

### Compose: the strip

Add it once, around the root; no screen changes:

```kotlin
ConnectivityStripLayout(monitor = monitor, enabled = currentRoute != Splash) {
    AppNavHost()
}
```

Everything is customisable:

```kotlin
ConnectivityStripLayout(
    monitor = monitor,
    position = StripPosition.Bottom,                 // Top (default) | Bottom
    mode = StripMode.Overlay,                        // PushContent (default, covers nothing) | Overlay
    scrimColor = Color.Unspecified,                  // system-bar spacer colour; default colorScheme.surface
    enter = fadeIn(), exit = fadeOut(),              // default expand/shrink + fade
    backOnline = BackOnlineConfig(duration = 2.seconds), // optional "Back online" confirmation; off by default
    strip = { state ->                               // default ConnectivityStrip(state)
        ConnectivityStrip(
            state = state,
            offlineText = stringResource(Res.string.no_internet),
            backOnlineText = "Back online",
            colors = ConnectivityStripDefaults.colors(offlineContainerColor = Color.DarkGray, offlineContentColor = Color.White),
            textStyle = MaterialTheme.typography.labelLarge,
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(12.dp),
            icon = { Icon(Icons.Default.WifiOff, null) },
            action = { TextButton(onClick = monitor::requestRecheck) { Text("Retry") } },
        )
    },
) { AppNavHost() }

// Or your own composable entirely:
ConnectivityStripLayout(monitor, strip = { state -> MyBanner(state) }) { AppNavHost() }

// Or drive it from your own state holder:
ConnectivityStripLayout(isConnected = uiState.isConnected) { AppNavHost() }
```

In `PushContent` mode the strip handles edge-to-edge insets without a layout jump. Screens that pad themselves for the status bar (`Scaffold`, `TopAppBar`, `windowInsetsPadding`) do not get a double gap. At the bottom, the strip sits above the navigation bar and the keyboard.

### Ktor / OkHttp hints

```kotlin
HttpClient(engine) { install(ConnectivityHints) { monitor = connectivityMonitor } }
OkHttpClient.Builder().addInterceptor(ConnectivityHintsInterceptor(monitor)).build()
```

A response (any status) marks online and resets the desktop probe timer. A connection failure triggers an immediate re-check, never an instant "offline". Cancelled requests are ignored. Hints are ignored on Android and iOS, where the OS signal is authoritative, unless `hints { applyOnNativePlatforms = true }`.

## Fail-safe behaviour (defaults)

The worst failure is a false "offline" alert, so every default leans toward *online*.

| Situation | Behaviour |
|---|---|
| Before the first reading | Online (`initialState = AssumeOnline`): no flash on start. |
| The OS monitor can't register (missing permission, Android's ~100-callback limit, ...) or a check throws | Online (`onMonitorFailure = AssumeOnline`), logged; never propagated to collectors. |
| `Unknown` / `MonitorError` status | `isConnected` stays `true`; only `status` shows it. |
| Android: Wi-Fi → cellular handover with a late `onLost` for the old network | Ignored: the current default network is tracked. |
| Android: network connected but not validated (captive portal, exhausted data) | Stays online (`ValidationPolicy.ConnectedToGoOffline`); `RequireValidated` is stricter. |
| Request fails | Re-check now (`RequestFailurePolicy.Recheck`), not offline. |
| Hints sent while nothing collects | Dropped: every restart starts with a fresh reading. |
| Desktop DNS lookup hangs | The whole check is bounded (`checkBound`, 7 s). A host whose lookup is still stuck is skipped, so threads never pile up. |
| Desktop: no network interface list available | Probe anyway (unknown is not offline). |
| Desktop: laptop wakes from sleep | Detected through a wall-clock jump; checks immediately. |
| Web: tab hidden | Probe paused, re-checked when visible. |
| Invalid configuration | Fails fast with an `IllegalArgumentException` naming the option, when the monitor is created. |

## All options

```kotlin
ConnectivityMonitor {
    validation = ValidationPolicy.ConnectedToGoOffline   // RequireValidated | ConnectedOnly
    initialState = InitialState.AssumeOnline             // AssumeOffline | Unknown
    onMonitorFailure = FailurePolicy.AssumeOnline        // AssumeOffline | Error
    reconnectedPolicy = ReconnectedPolicy.OnResumeIfWasOffline // LiveTransitionsOnly
    stopTimeout = 5.seconds                              // Duration.INFINITE = never stop
    scope = null                                         // parent scope; default SupervisorJob + Dispatchers.Default
    logger = ConnectivityLogger.None                     // Println | your own

    debounce { offline = Duration.ZERO; online = Duration.ZERO }   // e.g. offline = 1.seconds hides handovers

    hints {
        onRequestFailure = RequestFailurePolicy.Recheck  // MarkOffline | Ignore
        onResponse = ResponsePolicy.MarkOnline           // Ignore
        applyOnNativePlatforms = false
    }

    ios { useMainQueue = true }

    desktop {
        strategy = ProbeStrategy.Tcp                     // Http | Dns | InterfacesOnly | HintsOnly | Custom { ... }
        interfacePreCheck = true
        interfaceFilter = InterfaceFilter.Default        // e.g. InterfaceFilter { !it.name.startsWith("vEthernet") && ... }
        tcp {
            targets = ProbeTargets.PublicResolvers       // required for Tcp; or listOf(ProbeTargets.fromUrl(baseUrl))
            order = ProbeOrder.Sequential                // Parallel
            connectTimeout = 2.seconds
        }
        http {                                           // behind HTTP-only proxies / captive-portal detection
            url = ProbeTargets.Generate204Url            // or your health endpoint ("test URL")
            method = "HEAD"
            expectedStatus = 200..399                    // 204..204 for strict captive-portal detection
            useSystemProxy = true
            timeout = 3.seconds
            headers = mapOf("X-Api-Key" to key)
        }
        dns { hostname = "example.com" }
        checkBound = 7.seconds
        schedule = Schedule.Adaptive(online = 30.seconds, offline = 3.seconds)
        //  Schedule.Fixed(10.seconds)
        //  Schedule.Backoff(online = 30.seconds, initial = 1.seconds, factor = 2.0, max = 30.seconds, jitter = 0.1)
        //  Schedule.Custom { isOnline, consecutiveFailures -> ... }
        confirmOfflineAfter = 1
    }

    web {
        probe {
            enabled = false
            url = "/health"                              // same-origin recommended
            method = "HEAD"
            mode = FetchMode.SameOrigin                  // Cors | NoCors
            timeout = 3.seconds
            schedule = Schedule.Adaptive(online = 60.seconds, offline = 5.seconds)
            pauseWhenHidden = true
            onlyWhenNavigatorOnline = true
            confirmOfflineAfter = 1
        }
    }
}
```

The configuration is immutable. To change it at runtime, `close()` the monitor and create a new one.

### Desktop recipes

| Need | Configuration |
|---|---|
| No third-party hosts | `tcp { targets = listOf(ProbeTargets.fromUrl(apiBaseUrl)) }` or `strategy = ProbeStrategy.Http` with your health URL |
| Behind a corporate proxy | `strategy = ProbeStrategy.Http`, `useSystemProxy = true` (start the JVM with `-Djava.net.useSystemProxies=true`) |
| Minimal traffic | `schedule = Schedule.Adaptive(online = 2.minutes, offline = 10.seconds)` plus the Ktor/OkHttp hints |
| Fastest recovery | `schedule = Schedule.Backoff(initial = 500.milliseconds, max = 5.seconds)` |
| Flaky Wi-Fi | `confirmOfflineAfter = 2` or `debounce { offline = 2.seconds }` |
| Zero traffic | `strategy = ProbeStrategy.InterfacesOnly` (virtual adapters can keep it "online") or `HintsOnly` |

**Privacy:** `ProbeTargets.PublicResolvers` contacts Cloudflare (1.1.1.1) and Google (8.8.8.8) with a TCP handshake on port 443. No payload is sent: about 4 packets per check, at most one check per 30 s while online. Nothing is contacted unless you choose targets.

## Architecture

```
connectivity-core/src
  commonMain   public API (ConnectivityMonitor, ConnectivityConfig, ConnectivityStatus, policies, config/*)
               internal pipeline: source → StatusReducer → debounce → distinct → stateIn(WhileSubscribed)
               internal ProbeLoop (schedule, confirmation, pause, wake detection) shared by desktop and web
  androidMain  ConnectivityManager default-network callback
  appleMain    NWPathMonitor
  jvmMain      interface pre-check + TCP/HTTP/DNS probes, bounded
  webMain      navigator.onLine + events, optional fetch probe (js + wasmJs)
build-logic/   convention plugins: connectivity.kmp.library, connectivity.kmp.jvm, connectivity.compose.library, connectivity.publish
sample/        Compose Multiplatform sample (Android, iOS, Desktop, Web) with an MVI ViewModel
docs/          the specification and decision log
```

For Clean Architecture, keep library types out of your domain: define `interface ConnectivityObserver { val isConnected: Flow<Boolean> }` there and adapt `ConnectivityMonitor` to it in your data/DI layer.

## Development

```
./gradlew check                       # all tests (JVM, Android host, JS/Wasm in headless Chrome; iOS on macOS)
./gradlew checkKotlinAbi              # public API unchanged? (updateKotlinAbi after intended changes)
./gradlew :sample:desktopApp:run      # desktop sample
./gradlew :sample:webApp:wasmJsBrowserDevelopmentRun
./gradlew publishToMavenLocal
```

Android sample: open the project in Android Studio and run `sample:androidApp`. iOS sample: open `sample/iosApp/iosApp.xcodeproj` in Xcode.

Publishing to Maven Central: tag `vX.Y.Z` (see `.github/workflows/publish.yml`). It needs the `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY` and `SIGNING_KEY_PASSWORD` secrets, and a verified `io.github.lexmerciful` namespace on central.sonatype.com.

## Licence

Apache License 2.0, see [LICENSE](LICENSE).
