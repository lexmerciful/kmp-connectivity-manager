package io.github.lexmerciful.connectivity

import io.github.lexmerciful.connectivity.internal.AppleConnectivitySource
import io.github.lexmerciful.connectivity.internal.DefaultConnectivityMonitor

/**
 * Creates the iOS monitor, backed by `NWPathMonitor`. Create one per process.
 *
 * From Swift, observe it with `observe(onChange:)`, which returns a handle to `close()`.
 *
 * @throws IllegalArgumentException if the configuration is invalid.
 */
public fun ConnectivityMonitor(config: ConnectivityConfig): ConnectivityMonitor =
    DefaultConnectivityMonitor(config, AppleConnectivitySource(config.ios.useMainQueue, config.hints.applyOnNativePlatforms))

/** Builds the configuration with [block] and creates the iOS monitor; see the overload above. */
public fun ConnectivityMonitor(block: ConnectivityConfig.Builder.() -> Unit = {}): ConnectivityMonitor =
    ConnectivityMonitor(ConnectivityConfig(block))
