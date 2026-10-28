package io.github.lexmerciful.connectivity.internal

import io.github.lexmerciful.connectivity.ConnectivityConfig
import io.github.lexmerciful.connectivity.ConnectivityLogger
import io.github.lexmerciful.connectivity.ConnectivityMonitor
import io.github.lexmerciful.connectivity.ConnectivityStatus
import io.github.lexmerciful.connectivity.DisconnectReason
import io.github.lexmerciful.connectivity.InitialState
import io.github.lexmerciful.connectivity.ReconnectedPolicy
import io.github.lexmerciful.connectivity.RequestFailurePolicy
import io.github.lexmerciful.connectivity.ResponsePolicy
import io.github.lexmerciful.connectivity.isConnected
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.time.Duration

/**
 * The shared pipeline behind every platform:
 * source → [StatusReducer] (validation + failure policy) → catch → debounce → distinct → reconnect
 * tracking → `stateIn(WhileSubscribed(stopTimeout))`.
 */
internal class DefaultConnectivityMonitor(
    private val config: ConnectivityConfig,
    private val source: PlatformConnectivitySource,
) : ConnectivityMonitor {

    private val job = SupervisorJob(config.scope?.coroutineContext?.get(Job))
    private val scope = CoroutineScope((config.scope?.coroutineContext ?: Dispatchers.Default) + job)
    private val logger = config.logger

    /** No replay: hints sent while nobody collects are dropped, so a restart always reads fresh. */
    private val hints = MutableSharedFlow<Hint>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val reconnectedEvents =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Last state reported by any run; `null` until the first reading of the process. */
    @Volatile
    private var lastKnownConnected: Boolean? = null

    private val initialStatus: ConnectivityStatus = when (config.initialState) {
        InitialState.AssumeOnline -> ConnectivityStatus.Connected()
        InitialState.AssumeOffline -> ConnectivityStatus.Disconnected(DisconnectReason.NoNetwork)
        InitialState.Unknown -> ConnectivityStatus.Unknown
    }

    private val started: SharingStarted =
        if (config.stopTimeout == Duration.INFINITE) SharingStarted.Lazily
        else SharingStarted.WhileSubscribed(stopTimeoutMillis = config.stopTimeout.inWholeMilliseconds)

    override val status: StateFlow<ConnectivityStatus> = flow {
        val reducer = StatusReducer(config.validation, config.onMonitorFailure)
        source.events(hints).collect { raw ->
            if (raw is RawStatus.MonitorFailed) {
                logger.log(ConnectivityLogger.Level.Warning, "Connectivity monitor failed; applying ${config.onMonitorFailure}", raw.cause)
            }
            reducer.reduce(raw)?.let { emit(it) }
        }
    }
        .catch { cause ->
            logger.log(ConnectivityLogger.Level.Error, "Connectivity source crashed; applying ${config.onMonitorFailure}", cause)
            emit(config.onMonitorFailure.statusFor(cause))
        }
        .debounceTransitions(offline = config.debounce.offline, online = config.debounce.online)
        .distinctUntilChanged()
        .trackReconnects()
        .stateIn(scope, started, initialStatus)

    override val isConnected: StateFlow<Boolean> =
        status.map { it.isConnected }.stateIn(scope, SharingStarted.WhileSubscribed(), initialStatus.isConnected)

    /** Collecting this also keeps monitoring running, like [status]. */
    override val reconnected: Flow<Unit> = channelFlow {
        launch { status.collect { } }
        reconnectedEvents.collect { send(it) }
    }

    override fun reportReachable() {
        if (config.hints.onResponse == ResponsePolicy.MarkOnline) hints.tryEmit(Hint.Reachable)
    }

    override fun requestRecheck() {
        when (config.hints.onRequestFailure) {
            RequestFailurePolicy.Recheck -> hints.tryEmit(Hint.Recheck)
            RequestFailurePolicy.MarkOffline -> hints.tryEmit(Hint.MarkOffline)
            RequestFailurePolicy.Ignore -> Unit
        }
    }

    override fun close() {
        job.cancel()
    }

    private fun Flow<ConnectivityStatus>.trackReconnects(): Flow<ConnectivityStatus> {
        val upstream = this
        return flow {
            var firstOfRun = true
            upstream.collect { status ->
                val connected = status.isConnected
                val wasOffline = lastKnownConnected == false
                val fire = wasOffline && connected &&
                    (!firstOfRun || config.reconnectedPolicy == ReconnectedPolicy.OnResumeIfWasOffline)
                lastKnownConnected = connected
                firstOfRun = false
                emit(status)
                if (fire) reconnectedEvents.tryEmit(Unit)
            }
        }
    }
}
