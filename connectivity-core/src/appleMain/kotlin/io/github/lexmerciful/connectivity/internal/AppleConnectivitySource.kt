package io.github.lexmerciful.connectivity.internal

import io.github.lexmerciful.connectivity.Transport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import platform.Network.nw_interface_type_cellular
import platform.Network.nw_interface_type_other
import platform.Network.nw_interface_type_wifi
import platform.Network.nw_interface_type_wired
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_constrained
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfiable
import platform.Network.nw_path_status_satisfied
import platform.Network.nw_path_t
import platform.Network.nw_path_uses_interface_type
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create

/**
 * iOS / macOS source. A cancelled `nw_path_monitor` cannot be restarted, so each monitoring run creates
 * a new one. The monitor calls the update handler right away with the current path.
 */
internal class AppleConnectivitySource(
    private val useMainQueue: Boolean,
    private val applyHints: Boolean,
) : PlatformConnectivitySource {

    override fun events(hints: Flow<Hint>): Flow<RawStatus> = callbackFlow {
        val monitor = nw_path_monitor_create()
        nw_path_monitor_set_update_handler(monitor) { path -> trySend(path.toRawStatus()) }
        val queue = if (useMainQueue) dispatch_get_main_queue() else dispatch_queue_create(QUEUE_LABEL, null)
        nw_path_monitor_set_queue(monitor, queue)
        nw_path_monitor_start(monitor)

        if (applyHints) {
            launch { hints.collect { hint -> hint.toOverride()?.let { trySend(it) } } }
        }

        awaitClose { nw_path_monitor_cancel(monitor) }
    }.buffer(Channel.UNLIMITED)

    private fun nw_path_t.toRawStatus(): RawStatus.Path = RawStatus.Path(
        status = when (nw_path_get_status(this)) {
            nw_path_status_satisfied -> PathStatus.Satisfied
            nw_path_status_satisfiable -> PathStatus.RequiresConnection
            else -> PathStatus.Unsatisfied
        },
        transport = when {
            nw_path_uses_interface_type(this, nw_interface_type_wifi) -> Transport.Wifi
            nw_path_uses_interface_type(this, nw_interface_type_cellular) -> Transport.Cellular
            nw_path_uses_interface_type(this, nw_interface_type_wired) -> Transport.Ethernet
            nw_path_uses_interface_type(this, nw_interface_type_other) -> Transport.Other
            else -> Transport.Unknown
        },
        isExpensive = nw_path_is_expensive(this),
        isConstrained = nw_path_is_constrained(this),
    )

    private companion object {
        const val QUEUE_LABEL = "io.github.lexmerciful.connectivity"
    }
}
