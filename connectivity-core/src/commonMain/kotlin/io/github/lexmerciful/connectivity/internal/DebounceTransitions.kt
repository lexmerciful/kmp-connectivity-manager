package io.github.lexmerciful.connectivity.internal

import io.github.lexmerciful.connectivity.ConnectivityStatus
import io.github.lexmerciful.connectivity.isConnected
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration

/**
 * Delays online → offline by [offline] and offline → online by [online]. A transition that is reverted
 * within its delay is never emitted. The first value of a run and same-direction updates (metadata
 * changes) are emitted immediately.
 */
internal fun Flow<ConnectivityStatus>.debounceTransitions(offline: Duration, online: Duration): Flow<ConnectivityStatus> {
    if (offline == Duration.ZERO && online == Duration.ZERO) return this
    val upstream = this
    return channelFlow {
        val lock = Mutex()
        var lastEmittedConnected: Boolean? = null
        var pending: Job? = null
        upstream.collect { status ->
            lock.withLock {
                pending?.cancel()
                pending = null
                val connected = status.isConnected
                val wait = when (val last = lastEmittedConnected) {
                    null, connected -> Duration.ZERO
                    else -> if (connected) online else offline
                }
                if (wait == Duration.ZERO) {
                    lastEmittedConnected = connected
                    send(status)
                } else {
                    pending = launch {
                        delay(wait)
                        lock.withLock {
                            lastEmittedConnected = connected
                            send(status)
                        }
                    }
                }
            }
        }
    }
}
