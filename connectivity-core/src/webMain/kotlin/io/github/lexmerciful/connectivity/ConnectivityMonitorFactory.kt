package io.github.lexmerciful.connectivity

import io.github.lexmerciful.connectivity.config.requireProbeConfigured
import io.github.lexmerciful.connectivity.internal.DefaultConnectivityMonitor
import io.github.lexmerciful.connectivity.internal.SystemBrowserApi
import io.github.lexmerciful.connectivity.internal.WebConnectivitySource

/**
 * Creates the web (JS / Wasm) monitor, backed by `navigator.onLine` and the `online`/`offline` events,
 * plus the optional fetch probe (`web { probe { ... } }`). Create one per page.
 *
 * @throws IllegalArgumentException if the configuration is invalid or the probe is enabled without a URL.
 */
public fun ConnectivityMonitor(config: ConnectivityConfig): ConnectivityMonitor {
    config.web.requireProbeConfigured()
    return DefaultConnectivityMonitor(config, WebConnectivitySource(config.web, SystemBrowserApi()))
}

/** Builds the configuration with [block] and creates the web monitor; see the overload above. */
public fun ConnectivityMonitor(block: ConnectivityConfig.Builder.() -> Unit = {}): ConnectivityMonitor =
    ConnectivityMonitor(ConnectivityConfig(block))
