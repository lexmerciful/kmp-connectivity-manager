package io.github.lexmerciful.connectivity

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Tells whether the device can reach the internet.
 *
 * Create **one** monitor per process (keep it in DI or a singleton) with the platform factory:
 * `ConnectivityMonitor(context) { ... }` on Android, `ConnectivityMonitor { ... }` elsewhere.
 * Monitoring runs only while something collects [isConnected], [status] or [reconnected], and stops
 * `stopTimeout` after the last collector leaves.
 */
public interface ConnectivityMonitor : AutoCloseable {

    /**
     * `true` = internet reachable under the configured policy. Hot, conflated, replays the latest value,
     * and never emits the same value twice in a row. Only [ConnectivityStatus.Disconnected] reads as `false`.
     */
    public val isConnected: StateFlow<Boolean>

    /** Richer state: transport, metered, constrained, disconnect reason. */
    public val status: StateFlow<ConnectivityStatus>

    /** One event per offline → online transition (after debounce), per [ReconnectedPolicy]. Not replayed. */
    public val reconnected: Flow<Unit>

    /**
     * A response arrived from the network (any HTTP status). Cheap, thread-safe, never blocks.
     * Ignored while nobody collects, and on Android/iOS unless [HintConfig.applyOnNativePlatforms].
     */
    public fun reportReachable()

    /**
     * A request failed with an I/O error. Cheap, thread-safe, never blocks.
     * Ignored while nobody collects, and on Android/iOS unless [HintConfig.applyOnNativePlatforms].
     */
    public fun requestRecheck()

    /** Stops monitoring for good and releases platform resources. Hint calls become no-ops. */
    override fun close()
}

/**
 * Calls [onChange] with every [ConnectivityMonitor.isConnected] value on the main thread (or
 * `Dispatchers.Default` where no main dispatcher exists). For Swift and for code without coroutines.
 *
 * @return a handle; `close()` it to stop observing.
 */
public fun ConnectivityMonitor.observe(onChange: (Boolean) -> Unit): AutoCloseable =
    collectIn(defaultObserverDispatcher()) { isConnected.collect { onChange(it) } }

/** Like [observe], for [ConnectivityMonitor.status]. */
public fun ConnectivityMonitor.observeStatus(onChange: (ConnectivityStatus) -> Unit): AutoCloseable =
    collectIn(defaultObserverDispatcher()) { status.collect { onChange(it) } }

/** Like [observe], for [ConnectivityMonitor.reconnected]. */
public fun ConnectivityMonitor.observeReconnected(onReconnected: () -> Unit): AutoCloseable =
    collectIn(defaultObserverDispatcher()) { reconnected.collect { onReconnected() } }

private fun collectIn(dispatcher: CoroutineDispatcher, block: suspend () -> Unit): AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    scope.launch { block() }
    return AutoCloseable { scope.cancel() }
}

private fun defaultObserverDispatcher(): CoroutineDispatcher = try {
    Dispatchers.Main.immediate.also { it.isDispatchNeeded(EmptyCoroutineContext) }
} catch (_: Throwable) {
    // JVM without kotlinx-coroutines-swing/javafx: no main dispatcher.
    Dispatchers.Default
}
