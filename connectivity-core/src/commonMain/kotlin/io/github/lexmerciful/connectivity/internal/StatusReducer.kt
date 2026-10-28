package io.github.lexmerciful.connectivity.internal

import io.github.lexmerciful.connectivity.ConnectivityStatus
import io.github.lexmerciful.connectivity.DisconnectReason
import io.github.lexmerciful.connectivity.FailurePolicy
import io.github.lexmerciful.connectivity.ValidationPolicy

/**
 * Turns [RawStatus] events into [ConnectivityStatus] values according to the [ValidationPolicy] and
 * [FailurePolicy]. One instance per monitoring run; not thread-safe (the pipeline calls it sequentially).
 */
internal class StatusReducer(
    private val validation: ValidationPolicy,
    private val failurePolicy: FailurePolicy,
) {
    private var current: ConnectivityStatus? = null

    /** Android: the network the OS currently reports as default, so a late `onLost` of an old one is ignored. */
    private var defaultNetwork: String? = null
    private var snapshot: NetworkSnapshot? = null
    private var blocked = false

    /** @return the new status, or `null` when the event does not change the state. */
    fun reduce(event: RawStatus): ConnectivityStatus? {
        val next = when (event) {
            is RawStatus.Network -> onNetwork(event)
            is RawStatus.Lost -> onLost(event)
            is RawStatus.Blocked -> onBlocked(event)
            is RawStatus.Path -> onPath(event)
            is RawStatus.Reachability ->
                if (event.reachable) snapshot?.toConnected() ?: ConnectivityStatus.Connected()
                else ConnectivityStatus.Disconnected(event.reason)
            is RawStatus.MonitorFailed -> failurePolicy.statusFor(event.cause)
        }
        if (next != null) current = next
        return next
    }

    private fun onNetwork(event: RawStatus.Network): ConnectivityStatus? {
        if (event.networkId != defaultNetwork) blocked = false
        defaultNetwork = event.networkId
        val caps = event.snapshot
        snapshot = caps
        return when (validation) {
            ValidationPolicy.ConnectedToGoOffline -> when {
                event.isInitial ->
                    if (caps.hasInternet) caps.toConnected() else ConnectivityStatus.Disconnected(DisconnectReason.NoNetwork)
                caps.hasInternet && caps.isValidated -> caps.toConnected()
                // Losing validation never reports offline; while connected, keep the metadata fresh.
                current is ConnectivityStatus.Connected && caps.hasInternet -> caps.toConnected()
                else -> null
            }
            ValidationPolicy.RequireValidated -> strict(caps)
            ValidationPolicy.ConnectedOnly -> when {
                event.isInitial ->
                    if (caps.hasInternet) caps.toConnected() else ConnectivityStatus.Disconnected(DisconnectReason.NoNetwork)
                caps.isCaptivePortal -> null
                caps.hasInternet -> caps.toConnected()
                else -> null
            }
        }
    }

    private fun onLost(event: RawStatus.Lost): ConnectivityStatus? {
        val tracked = defaultNetwork
        if (event.networkId != null && tracked != null && event.networkId != tracked) return null // stale onLost
        defaultNetwork = null
        snapshot = null
        blocked = false
        return ConnectivityStatus.Disconnected(DisconnectReason.NoNetwork)
    }

    private fun onBlocked(event: RawStatus.Blocked): ConnectivityStatus? {
        if (event.networkId != defaultNetwork) return null
        blocked = event.blocked
        return if (validation == ValidationPolicy.RequireValidated) snapshot?.let(::strict) else null
    }

    private fun onPath(event: RawStatus.Path): ConnectivityStatus = when (event.status) {
        PathStatus.Satisfied -> event.toConnected()
        PathStatus.RequiresConnection ->
            if (validation == ValidationPolicy.ConnectedOnly) event.toConnected()
            else ConnectivityStatus.Disconnected(DisconnectReason.NoNetwork)
        PathStatus.Unsatisfied -> ConnectivityStatus.Disconnected(DisconnectReason.NoNetwork)
    }

    private fun strict(caps: NetworkSnapshot): ConnectivityStatus = when {
        blocked -> ConnectivityStatus.Disconnected(DisconnectReason.Blocked)
        caps.hasInternet && caps.isValidated && !caps.isCaptivePortal -> caps.toConnected()
        caps.hasInternet -> ConnectivityStatus.Disconnected(DisconnectReason.NotValidated)
        else -> ConnectivityStatus.Disconnected(DisconnectReason.NoNetwork)
    }

    private fun NetworkSnapshot.toConnected() = ConnectivityStatus.Connected(
        transport = transport,
        isMetered = isMetered,
        isConstrained = isConstrained,
        isValidated = isValidated,
    )

    private fun RawStatus.Path.toConnected() = ConnectivityStatus.Connected(
        transport = transport,
        isMetered = isExpensive,
        isConstrained = isConstrained,
    )
}

internal fun FailurePolicy.statusFor(cause: Throwable): ConnectivityStatus = when (this) {
    FailurePolicy.AssumeOnline -> ConnectivityStatus.Connected()
    FailurePolicy.AssumeOffline -> ConnectivityStatus.Disconnected(DisconnectReason.MonitorUnavailable)
    FailurePolicy.Error -> ConnectivityStatus.MonitorError(cause)
}
