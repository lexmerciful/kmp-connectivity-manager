package io.github.lexmerciful.connectivity.config

import io.github.lexmerciful.connectivity.ConnectivityDsl
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Web (JS / Wasm) settings. Ignored on other platforms.
 *
 * By default the monitor uses `navigator.onLine` and the `online`/`offline` events only (zero traffic).
 */
public class WebConfig internal constructor(public val probe: WebProbeConfig) {

    @ConnectivityDsl
    public class Builder internal constructor() {
        private val probe = WebProbeConfig.Builder()

        /** Optional fetch probe for "internet actually reachable" (captive portals, dead upstream). */
        public fun probe(block: WebProbeConfig.Builder.() -> Unit) {
            probe.block()
        }

        internal fun build(): WebConfig = WebConfig(probe.build())
    }
}

/** The `fetch` mode of the web probe. */
public enum class FetchMode {
    /** Default. Same-origin request: no CORS, no third-party privacy concern. */
    SameOrigin,

    /** Cross-origin request; the target must send CORS headers. */
    Cors,

    /** Cross-origin request with an opaque response: proves reachability, cannot detect captive portals. */
    NoCors,
}

public class WebProbeConfig internal constructor(
    public val enabled: Boolean,
    public val url: String?,
    public val method: String,
    public val mode: FetchMode,
    public val timeout: Duration,
    public val schedule: Schedule,
    public val pauseWhenHidden: Boolean,
    public val onlyWhenNavigatorOnline: Boolean,
    public val confirmOfflineAfter: Int,
) {
    @ConnectivityDsl
    public class Builder internal constructor() {
        /** Default `false`. */
        public var enabled: Boolean = false

        /** Required when [enabled]; a same-origin path such as `/health` is recommended. */
        public var url: String? = null

        /** `HEAD` (default) or `GET`. */
        public var method: String = "HEAD"
        public var mode: FetchMode = FetchMode.SameOrigin

        /** Abort the fetch after this. Default 3 s. */
        public var timeout: Duration = 3.seconds

        /** Default [Schedule.Adaptive] (60 s online, 5 s offline). */
        public var schedule: Schedule = Schedule.Adaptive(online = 60.seconds, offline = 5.seconds)

        /** Stop probing while the tab is hidden; re-check when it becomes visible. Default `true`. */
        public var pauseWhenHidden: Boolean = true

        /** Skip the fetch and report offline when `navigator.onLine` is false (it is reliable). Default `true`. */
        public var onlyWhenNavigatorOnline: Boolean = true

        /** Consecutive failed probes before offline is reported. Default 1. */
        public var confirmOfflineAfter: Int = 1

        internal fun build(): WebProbeConfig {
            url?.let { require(it.isNotBlank()) { "web.probe.url must not be blank" } }
            require(method.uppercase() in ALLOWED_METHODS) { "web.probe.method must be one of $ALLOWED_METHODS, was $method" }
            requirePositive(timeout, "web.probe.timeout")
            schedule.validate("web.probe.schedule")
            require(confirmOfflineAfter >= 1) { "web.probe.confirmOfflineAfter must be >= 1, was $confirmOfflineAfter" }
            return WebProbeConfig(
                enabled, url, method.uppercase(), mode, timeout, schedule,
                pauseWhenHidden, onlyWhenNavigatorOnline, confirmOfflineAfter,
            )
        }

        private companion object {
            val ALLOWED_METHODS = setOf("HEAD", "GET")
        }
    }
}

internal fun WebConfig.requireProbeConfigured() {
    if (probe.enabled) {
        require(probe.url != null) {
            "web.probe.enabled is true but web.probe.url is not set. A same-origin health path such as \"/health\" is recommended."
        }
    }
}
