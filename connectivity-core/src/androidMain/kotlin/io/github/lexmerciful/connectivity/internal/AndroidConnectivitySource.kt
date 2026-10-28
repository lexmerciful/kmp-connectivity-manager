package io.github.lexmerciful.connectivity.internal

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/**
 * Android source: a synchronous first reading, then default-network callbacks. One registration per
 * monitoring run, however many collectors the monitor has.
 */
internal class AndroidConnectivitySource(
    private val api: AndroidNetworkApi,
    private val applyHints: Boolean,
) : PlatformConnectivitySource {

    override fun events(hints: Flow<Hint>): Flow<RawStatus> = callbackFlow {
        val listener = object : AndroidNetworkApi.Listener {
            override fun onCapabilities(networkId: String, snapshot: NetworkSnapshot) {
                trySend(RawStatus.Network(networkId, snapshot, isInitial = false))
            }

            override fun onLost(networkId: String) {
                trySend(RawStatus.Lost(networkId))
            }

            override fun onBlocked(networkId: String, blocked: Boolean) {
                trySend(RawStatus.Blocked(networkId, blocked))
            }
        }

        val registration = try {
            trySend(api.currentDefaultNetwork())
            api.register(listener)
        } catch (e: RuntimeException) {
            // SecurityException, TooManyRequestsException (API 30+), missing system service.
            trySend(RawStatus.MonitorFailed(e))
            null
        }

        if (applyHints) {
            launch {
                hints.collect { hint ->
                    val event = hint.toOverride() ?: runCatching { api.currentDefaultNetwork() }.getOrNull()
                    if (event != null) trySend(event)
                }
            }
        }

        awaitClose { registration?.let { runCatching { it.close() } } }
    }.buffer(Channel.UNLIMITED)
}
