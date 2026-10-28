package io.github.lexmerciful.connectivity

/**
 * Connectivity state reported by a [ConnectivityMonitor].
 *
 * [ConnectivityMonitor.isConnected] is `false` only for [Disconnected]; [Unknown] and [MonitorError]
 * count as connected so that the UI never shows a false "offline" alert.
 */
public sealed interface ConnectivityStatus {

    /** No reading yet. Only used with [InitialState.Unknown]. */
    public data object Unknown : ConnectivityStatus

    /**
     * The internet is reachable under the configured [ValidationPolicy].
     *
     * Metadata fields are `null` when the platform cannot tell.
     */
    public data class Connected(
        val transport: Transport = Transport.Unknown,
        /** Android: not `NET_CAPABILITY_NOT_METERED`; iOS: `isExpensive`. */
        val isMetered: Boolean? = null,
        /** iOS: Low Data Mode; Android: Data Saver (best effort). */
        val isConstrained: Boolean? = null,
        /** Android: `NET_CAPABILITY_VALIDATED`. */
        val isValidated: Boolean? = null,
    ) : ConnectivityStatus

    /** The internet is not reachable. */
    public data class Disconnected(val reason: DisconnectReason = DisconnectReason.NoNetwork) : ConnectivityStatus

    /** The platform monitor failed. Only used with [FailurePolicy.Error]. */
    public data class MonitorError(val cause: Throwable) : ConnectivityStatus
}

/** The network transport behind a [ConnectivityStatus.Connected] status. */
public enum class Transport { Wifi, Cellular, Ethernet, Vpn, Other, Unknown }

/** Why a [ConnectivityStatus.Disconnected] status was reported. */
public enum class DisconnectReason {
    /** No usable network at all. */
    NoNetwork,

    /** A network exists but the OS has not validated it ([ValidationPolicy.RequireValidated] only). */
    NotValidated,

    /** The OS blocks this app's traffic (Android 29+, [ValidationPolicy.RequireValidated] only). */
    Blocked,

    /** The desktop/web reachability probe failed. */
    ProbeFailed,

    /** A request failed and [RequestFailurePolicy.MarkOffline] is configured. */
    RequestFailed,

    /** The platform monitor failed and [FailurePolicy.AssumeOffline] is configured. */
    MonitorUnavailable,
}

/** `true` unless this is [ConnectivityStatus.Disconnected]. */
public val ConnectivityStatus.isConnected: Boolean
    get() = this !is ConnectivityStatus.Disconnected
