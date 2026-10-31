# KMP Connectivity

**Internet connectivity monitor for Kotlin Multiplatform**: know whether the device can actually reach the internet on Android, iOS, Desktop (JVM) and Web (JS + Wasm), as a `StateFlow<Boolean>`, and show a "No internet connection" banner in Jetpack Compose or Compose Multiplatform with one line.

[![Maven Central](https://img.shields.io/maven-central/v/io.github.lexmerciful/connectivity-core?label=Maven%20Central)](https://central.sonatype.com/namespace/io.github.lexmerciful)
[![CI](https://github.com/lexmerciful/kmp-connectivity-manager/actions/workflows/ci.yml/badge.svg)](https://github.com/lexmerciful/kmp-connectivity-manager/actions/workflows/ci.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose Multiplatform](https://img.shields.io/badge/Compose%20Multiplatform-1.12-4285F4)](https://www.jetbrains.com/compose-multiplatform/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

![Android](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)
![iOS](https://img.shields.io/badge/platform-iOS-000000?logo=apple&logoColor=white)
![Desktop](https://img.shields.io/badge/platform-Desktop%20JVM-E76F00?logo=openjdk&logoColor=white)
![Web](https://img.shields.io/badge/platform-JS%20%7C%20Wasm-F7DF1E?logo=webassembly&logoColor=black)

**[API reference](https://lexmerciful.github.io/kmp-connectivity-manager/)** · **[Changelog](CHANGELOG.md)** · **[Sample app](sample/)**

```kotlin
val monitor = ConnectivityMonitor(context)           // ConnectivityMonitor() on iOS and Web

monitor.isConnected.collect { online -> /* ... */ }  // StateFlow<Boolean>

ConnectivityStripLayout(monitor) { AppNavHost() }    // "No internet connection" strip, edge-to-edge aware
```

## Why

Checking "is there a network?" is easy; telling the user "you are offline" **without false alarms** is not. Wi-Fi → cellular handovers report a brief loss, captive portals report a network with no internet, desktop JVMs have no OS signal at all, and a monitor that fails on start should never lock the user out. This library uses each platform's own signal, adds the missing pieces (bounded probes on desktop, an optional probe on the web), and makes every default lean toward *online*.

- **One API on every target**: `isConnected`, a richer `status` (Wi-Fi, cellular, Ethernet, VPN; metered; Low Data Mode; disconnect reason) and a `reconnected` event for auto-retry.
- **Native signals**: Android `ConnectivityManager.NetworkCallback` (default network), iOS `NWPathMonitor`, browser `navigator.onLine`, desktop TCP / HTTP / DNS probes you choose.
- **No false "offline"**: handover-safe, validation and failure policies, debounce, offline confirmation, wake-from-sleep detection.
- **Compose UI included**: a "No internet connection" / "Back online" strip with insets handling, top or bottom, push or overlay, Material 3 defaults, fully customisable.
- **Faster detection from your HTTP client**: optional Ktor plugin and OkHttp interceptor.
- **Swift-friendly**: `monitor.observe { connected in ... }`, no coroutines needed.
- **Lightweight and private**: monitoring runs only while collected and stops 5 s after the last collector; nothing is contacted unless you configure it.
- **Built to be depended on**: explicit API mode, binary-compatibility (ABI) checks, and tests on every platform in CI.

## Platforms

| Platform | Signal | Modules |
|---|---|---|
| Android (minSdk 24) | `ConnectivityManager` default-network callback | core, compose, ktor, okhttp |
| iOS (arm64, simulator arm64) | `NWPathMonitor` | core, compose, ktor |
| Desktop JVM (Windows, macOS, Linux) | Network interfaces + bounded TCP / HTTP / DNS probe | core, compose, ktor, okhttp |
| Web: Kotlin/JS and Kotlin/Wasm | `navigator.onLine` + `online`/`offline` events, optional fetch probe | core, compose, ktor |

## Installation

Available on Maven Central:

| Artifact | What it gives you |
|---|---|
| `connectivity-core` | `ConnectivityMonitor`: `isConnected`, `status`, `reconnected`. Coroutines only, no UI. |
| `connectivity-compose` | `ConnectivityStripLayout` + `ConnectivityStrip` (Material 3 defaults, every visual overridable, fully custom slot). Works in Jetpack Compose and Compose Multiplatform. |
| `connectivity-ktor` | `ConnectivityHints` Ktor client plugin: request outcomes speed up detection. |
| `connectivity-okhttp` | `ConnectivityHintsInterceptor` for OkHttp (Android / JVM). |

```kotlin
// build.gradle.kts: commonMain.dependencies { ... } in a KMP module, or dependencies { ... } in an Android module
implementation("io.github.lexmerciful:connectivity-core:0.1.0")
implementation("io.github.lexmerciful:connectivity-compose:0.1.0") // optional UI
implementation("io.github.lexmerciful:connectivity-ktor:0.1.0")    // optional
```

<details>
<summary>Version catalog (<code>libs.versions.toml</code>)</summary>

```toml
[versions]
connectivity = "0.1.0"

[libraries]
connectivity-core = { module = "io.github.lexmerciful:connectivity-core", version.ref = "connectivity" }
connectivity-compose = { module = "io.github.lexmerciful:connectivity-compose", version.ref = "connectivity" }
connectivity-ktor = { module = "io.github.lexmerciful:connectivity-ktor", version.ref = "connectivity" }
connectivity-okhttp = { module = "io.github.lexmerciful:connectivity-okhttp", version.ref = "connectivity" }
```

</details>

The Android `ACCESS_NETWORK_STATE` permission is merged from the library manifest; there is nothing to add.

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
```

For Clean Architecture, keep library types out of your domain: define `interface ConnectivityObserver { val isConnected: Flow<Boolean> }` there and adapt `ConnectivityMonitor` to it in your data/DI layer.

## Development

```
./gradlew check                       # all tests (JVM, Android host, JS/Wasm in headless Chrome; iOS on macOS)
./gradlew checkKotlinAbi              # public API unchanged? (updateKotlinAbi after intended changes)
./gradlew :dokkaGenerate              # API docs → build/dokka/html
./gradlew :sample:desktopApp:run      # desktop sample
./gradlew :sample:webApp:wasmJsBrowserDevelopmentRun
./gradlew publishToMavenLocal -PVERSION_NAME=0.0.0-local && scripts/verify-publication.sh 0.0.0-local
```

Android sample: open the project in Android Studio and run `sample:androidApp`. iOS sample: open `sample/iosApp/iosApp.xcodeproj` in Xcode.

**CI** runs on every push and pull request: Linux (all tests, ABI check, samples, API docs), macOS (iOS simulator tests against the real `NWPathMonitor`, iOS compilation, and a full publication dry run checked by `scripts/verify-publication.sh`), and the desktop JVM tests on Windows and macOS.

**Releasing:** add a `## [X.Y.Z]` section to [CHANGELOG.md](CHANGELOG.md), then push the tag `vX.Y.Z`. The release workflow runs the full CI, publishes to Maven Central, creates the GitHub Release from that changelog section and deploys the API docs. It needs the `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY` and `SIGNING_KEY_PASSWORD` secrets, and a verified `io.github.lexmerciful` namespace on central.sonatype.com.

## FAQ

**How do I check the internet connection in Kotlin Multiplatform?**
Add `connectivity-core`, create one `ConnectivityMonitor` per process, and read `monitor.isConnected.value` or collect the `StateFlow`. The same code works in `commonMain` for Android, iOS, desktop and web.

**How do I show a "No internet connection" banner in Compose Multiplatform or Jetpack Compose?**
Wrap your root content in `ConnectivityStripLayout(monitor) { ... }` from `connectivity-compose`. No per-screen changes are needed.

**Does "connected" mean the internet is actually reachable?**
On Android it follows the system's validated-internet capability (see `ValidationPolicy`); on iOS, `NWPathMonitor`'s satisfied path; on desktop, a real probe to targets you choose; on the web, the browser's signal plus an optional probe of your own endpoint. Request outcomes from Ktor or OkHttp confirm it further.

**Can I use it from Swift or SwiftUI?**
Yes: `let handle = monitor.observe { connected in ... }`, then `handle.close()` to stop. No coroutine bridging library is needed.

**How do I retry requests when the connection comes back?**
Collect `monitor.reconnected`: it emits once per offline → online transition.

**Does it send network traffic?**
On Android, iOS and the web (by default), no. On desktop it probes only the targets you configure; see the privacy note above.

## Contributing

Issues and pull requests are welcome. Please run `./gradlew check checkKotlinAbi` before opening a pull request, and add a line to [CHANGELOG.md](CHANGELOG.md) for user-visible changes.

## Licence

Apache License 2.0, see [LICENSE](LICENSE).
