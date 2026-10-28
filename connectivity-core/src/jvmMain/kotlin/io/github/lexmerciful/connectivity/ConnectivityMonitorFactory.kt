package io.github.lexmerciful.connectivity

import io.github.lexmerciful.connectivity.config.requireProbeConfigured
import io.github.lexmerciful.connectivity.internal.DefaultConnectivityMonitor
import io.github.lexmerciful.connectivity.internal.DesktopConnectivitySource
import io.github.lexmerciful.connectivity.internal.DesktopReachabilityCheck

/**
 * Creates the desktop (JVM) monitor. Create one per process.
 *
 * The probe needs explicit targets; the library never contacts a third-party host on its own:
 * ```
 * ConnectivityMonitor {
 *     desktop { tcp { targets = ProbeTargets.PublicResolvers + ProbeTargets.fromUrl(apiBaseUrl) } }
 * }
 * ```
 *
 * @throws IllegalArgumentException if the configuration is invalid or the selected probe has no target.
 */
public fun ConnectivityMonitor(config: ConnectivityConfig): ConnectivityMonitor {
    config.desktop.requireProbeConfigured()
    val check = DesktopReachabilityCheck(config.desktop)
    return DefaultConnectivityMonitor(config, DesktopConnectivitySource(config.desktop, check::check))
}

/** Builds the configuration with [block] and creates the desktop monitor; see the overload above. */
public fun ConnectivityMonitor(block: ConnectivityConfig.Builder.() -> Unit = {}): ConnectivityMonitor =
    ConnectivityMonitor(ConnectivityConfig(block))
