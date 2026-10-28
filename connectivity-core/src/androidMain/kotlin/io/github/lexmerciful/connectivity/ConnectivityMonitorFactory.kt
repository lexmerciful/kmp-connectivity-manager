package io.github.lexmerciful.connectivity

import android.content.Context
import io.github.lexmerciful.connectivity.internal.AndroidConnectivitySource
import io.github.lexmerciful.connectivity.internal.DefaultConnectivityMonitor
import io.github.lexmerciful.connectivity.internal.SystemAndroidNetworkApi

/**
 * Creates the Android monitor, backed by `ConnectivityManager.registerDefaultNetworkCallback`.
 * Create one per process: every monitor registers its own callback, and Android limits an app to about 100.
 *
 * Safe to call with an Activity: only `context.applicationContext` is kept.
 *
 * @throws IllegalArgumentException if the configuration is invalid.
 */
public fun ConnectivityMonitor(context: Context, config: ConnectivityConfig): ConnectivityMonitor {
    val api = SystemAndroidNetworkApi(context.applicationContext)
    return DefaultConnectivityMonitor(config, AndroidConnectivitySource(api, config.hints.applyOnNativePlatforms))
}

/** Builds the configuration with [block] and creates the Android monitor; see the overload above. */
public fun ConnectivityMonitor(context: Context, block: ConnectivityConfig.Builder.() -> Unit = {}): ConnectivityMonitor =
    ConnectivityMonitor(context, ConnectivityConfig(block))
