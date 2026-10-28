package io.github.lexmerciful.connectivity.internal

import io.github.lexmerciful.connectivity.ConnectivityConfig
import io.github.lexmerciful.connectivity.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/** A controllable [PlatformConnectivitySource] that records runs and received hints. */
internal class FakeSource(private val onStart: suspend (Flow<Hint>) -> Unit = {}) : PlatformConnectivitySource {
    private val events = MutableSharedFlow<RawStatus>(extraBufferCapacity = 64)

    var starts = 0
        private set
    var activeRuns = 0
        private set
    val receivedHints = mutableListOf<Hint>()

    override fun events(hints: Flow<Hint>): Flow<RawStatus> = channelFlow {
        starts++
        activeRuns++
        try {
            launch { hints.collect { receivedHints += it } }
            onStart(hints)
            events.collect { send(it) }
        } finally {
            activeRuns--
        }
    }

    fun emit(event: RawStatus) {
        check(events.tryEmit(event)) { "buffer full" }
    }

    fun online() = emit(RawStatus.Reachability(true))
    fun offline() = emit(RawStatus.Reachability(false))
}

internal fun CoroutineScope.monitor(
    source: PlatformConnectivitySource,
    block: ConnectivityConfig.Builder.() -> Unit = {},
): DefaultConnectivityMonitor = DefaultConnectivityMonitor(
    ConnectivityConfig {
        scope = this@monitor
        block()
    },
    source,
)

internal fun snapshot(
    internet: Boolean = true,
    validated: Boolean = true,
    captive: Boolean = false,
    transport: Transport = Transport.Wifi,
) = NetworkSnapshot(
    hasInternet = internet,
    isValidated = validated,
    isCaptivePortal = captive,
    transport = transport,
    isMetered = false,
    isConstrained = false,
)
