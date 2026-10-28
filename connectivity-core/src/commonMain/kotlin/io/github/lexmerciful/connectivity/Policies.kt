package io.github.lexmerciful.connectivity

/** What counts as "connected" on platforms with native signals (Android, iOS). */
public enum class ValidationPolicy {
    /**
     * Default. Offline only when there is no network at all; online once a network is validated.
     * Losing validation alone never reports offline, so there are no false alerts.
     * The first reading only needs a connected network.
     */
    ConnectedToGoOffline,

    /** Offline while the default network is not validated, is a captive portal, or is blocked. */
    RequireValidated,

    /** Any network with internet capability is online; validation is ignored. */
    ConnectedOnly,
}

/** The status reported before the platform delivers its first reading. */
public enum class InitialState {
    /** Default. Report connected, so the strip never flashes on start. */
    AssumeOnline,

    /** Report disconnected until the first reading. */
    AssumeOffline,

    /** Report [ConnectivityStatus.Unknown] (which still counts as connected). */
    Unknown,
}

/** What to report when the platform monitor cannot run or a check throws. */
public enum class FailurePolicy {
    /** Default. Report connected: never show a false error. */
    AssumeOnline,

    /** Report [ConnectivityStatus.Disconnected] with [DisconnectReason.MonitorUnavailable]. */
    AssumeOffline,

    /** Report [ConnectivityStatus.MonitorError] (which still counts as connected). */
    Error,
}

/** When [ConnectivityMonitor.reconnected] fires. It never fires for the first reading of the process. */
public enum class ReconnectedPolicy {
    /**
     * Default. Fires on every offline → online transition, and also when monitoring restarts
     * (e.g. the app returns from the background) online after the last known state was offline.
     */
    OnResumeIfWasOffline,

    /** Fires only for offline → online transitions observed while monitoring is running. */
    LiveTransitionsOnly,
}

/** What a failed request ([ConnectivityMonitor.requestRecheck]) does. */
public enum class RequestFailurePolicy {
    /** Default. Run a fresh check now; a slow server is not "no internet". */
    Recheck,

    /** Report offline immediately until the next successful check or response. */
    MarkOffline,

    /** Do nothing. */
    Ignore,
}

/** What a received response ([ConnectivityMonitor.reportReachable]) does. */
public enum class ResponsePolicy {
    /** Default. Report online immediately; any HTTP status proves the network works. */
    MarkOnline,

    /** Do nothing. */
    Ignore,
}
