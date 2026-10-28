package io.github.lexmerciful.connectivity.internal

import io.github.lexmerciful.connectivity.DisconnectReason
import io.github.lexmerciful.connectivity.Transport
import kotlinx.coroutines.flow.Flow

/** Translates OS signals into [RawStatus] events. Knows nothing about policies, debounce or sharing. */
internal fun interface PlatformConnectivitySource {
    /**
     * Cold flow, collected once per monitoring run. [hints] only emits while this flow is collected.
     */
    fun events(hints: Flow<Hint>): Flow<RawStatus>
}

/** A hint from the app's HTTP layer, already filtered by the [io.github.lexmerciful.connectivity.HintConfig]. */
internal enum class Hint { Reachable, Recheck, MarkOffline }

/** Native platforms: the override a hint applies on top of the OS signal; `null` for [Hint.Recheck]. */
internal fun Hint.toOverride(): RawStatus.Reachability? = when (this) {
    Hint.Reachable -> RawStatus.Reachability(reachable = true)
    Hint.MarkOffline -> RawStatus.Reachability(reachable = false, reason = DisconnectReason.RequestFailed)
    Hint.Recheck -> null
}

/** Raw platform events, interpreted by [StatusReducer]. */
internal sealed interface RawStatus {

    /** Android: capabilities of the default network [networkId]. [isInitial] for synchronous reads. */
    data class Network(val networkId: String, val snapshot: NetworkSnapshot, val isInitial: Boolean) : RawStatus

    /** Android: [networkId] was lost; `null` = no active network at a synchronous read. */
    data class Lost(val networkId: String?) : RawStatus

    /** Android 29+: the OS blocks (or unblocks) this app's traffic on [networkId]. */
    data class Blocked(val networkId: String, val blocked: Boolean) : RawStatus

    /** iOS / macOS: an `nw_path` update. */
    data class Path(
        val status: PathStatus,
        val transport: Transport,
        val isExpensive: Boolean,
        val isConstrained: Boolean,
    ) : RawStatus

    /** Desktop / web / hints: a definitive reachability answer. */
    data class Reachability(
        val reachable: Boolean,
        val reason: DisconnectReason = DisconnectReason.ProbeFailed,
    ) : RawStatus

    /** The platform monitor or a check failed; the reducer applies the failure policy. */
    data class MonitorFailed(val cause: Throwable) : RawStatus
}

internal data class NetworkSnapshot(
    val hasInternet: Boolean,
    val isValidated: Boolean,
    val isCaptivePortal: Boolean,
    val transport: Transport,
    val isMetered: Boolean?,
    val isConstrained: Boolean?,
)

internal enum class PathStatus { Satisfied, RequiresConnection, Unsatisfied }
