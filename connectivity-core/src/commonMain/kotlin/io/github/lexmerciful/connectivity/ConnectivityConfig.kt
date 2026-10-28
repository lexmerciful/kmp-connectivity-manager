package io.github.lexmerciful.connectivity

import io.github.lexmerciful.connectivity.config.DesktopConfig
import io.github.lexmerciful.connectivity.config.WebConfig
import io.github.lexmerciful.connectivity.config.requireNonNegative
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Immutable monitor configuration. Every default reproduces the reference behaviour.
 *
 * Build it with [ConnectivityConfig] `{ ... }` (or pass the same block to a `ConnectivityMonitor` factory).
 * Invalid values fail fast with an [IllegalArgumentException] when the config is built.
 * To change the configuration later, `close()` the monitor and create a new one.
 */
public class ConnectivityConfig internal constructor(
    public val validation: ValidationPolicy,
    public val initialState: InitialState,
    public val onMonitorFailure: FailurePolicy,
    public val reconnectedPolicy: ReconnectedPolicy,
    /** How long monitoring keeps running after the last collector leaves. [Duration.INFINITE] = never stop. */
    public val stopTimeout: Duration,
    /** Parent scope for the monitor; `null` = its own `SupervisorJob` on `Dispatchers.Default`. */
    public val scope: CoroutineScope?,
    public val logger: ConnectivityLogger,
    public val debounce: DebounceConfig,
    public val hints: HintConfig,
    public val ios: IosConfig,
    public val desktop: DesktopConfig,
    public val web: WebConfig,
) {

    @ConnectivityDsl
    public class Builder internal constructor() {
        public var validation: ValidationPolicy = ValidationPolicy.ConnectedToGoOffline
        public var initialState: InitialState = InitialState.AssumeOnline
        public var onMonitorFailure: FailurePolicy = FailurePolicy.AssumeOnline
        public var reconnectedPolicy: ReconnectedPolicy = ReconnectedPolicy.OnResumeIfWasOffline
        public var stopTimeout: Duration = 5.seconds
        public var scope: CoroutineScope? = null
        public var logger: ConnectivityLogger = ConnectivityLogger.None

        private val debounce = DebounceConfig.Builder()
        private val hints = HintConfig.Builder()
        private val ios = IosConfig.Builder()
        private val desktop = DesktopConfig.Builder()
        private val web = WebConfig.Builder()

        /** Delay before a transition is reported; a flap shorter than the delay is never shown. Default off. */
        public fun debounce(block: DebounceConfig.Builder.() -> Unit) {
            debounce.block()
        }

        /** How hints from the app's HTTP layer are applied. */
        public fun hints(block: HintConfig.Builder.() -> Unit) {
            hints.block()
        }

        /** iOS settings; ignored elsewhere. */
        public fun ios(block: IosConfig.Builder.() -> Unit) {
            ios.block()
        }

        /** Desktop (JVM) settings; ignored elsewhere. */
        public fun desktop(block: DesktopConfig.Builder.() -> Unit) {
            desktop.block()
        }

        /** Web (JS / Wasm) settings; ignored elsewhere. */
        public fun web(block: WebConfig.Builder.() -> Unit) {
            web.block()
        }

        internal fun build(): ConnectivityConfig {
            require(stopTimeout == Duration.INFINITE || (!stopTimeout.isNegative() && stopTimeout.isFinite())) {
                "stopTimeout must be >= 0 or Duration.INFINITE, was $stopTimeout"
            }
            return ConnectivityConfig(
                validation = validation,
                initialState = initialState,
                onMonitorFailure = onMonitorFailure,
                reconnectedPolicy = reconnectedPolicy,
                stopTimeout = stopTimeout,
                scope = scope,
                logger = logger,
                debounce = debounce.build(),
                hints = hints.build(),
                ios = ios.build(),
                desktop = desktop.build(),
                web = web.build(),
            )
        }
    }
}

/**
 * Builds a [ConnectivityConfig].
 *
 * @throws IllegalArgumentException if a value is invalid; the message names the offending option.
 */
public fun ConnectivityConfig(block: ConnectivityConfig.Builder.() -> Unit = {}): ConnectivityConfig =
    ConnectivityConfig.Builder().apply(block).build()

public class DebounceConfig internal constructor(
    /** Delay before online → offline is reported. Default 0. Recommended 1 s to hide Wi-Fi → cellular handovers. */
    public val offline: Duration,
    /** Delay before offline → online is reported. Default 0. */
    public val online: Duration,
) {
    @ConnectivityDsl
    public class Builder internal constructor() {
        public var offline: Duration = Duration.ZERO
        public var online: Duration = Duration.ZERO

        internal fun build(): DebounceConfig {
            requireNonNegative(offline, "debounce.offline")
            requireNonNegative(online, "debounce.online")
            return DebounceConfig(offline, online)
        }
    }
}

public class HintConfig internal constructor(
    public val onRequestFailure: RequestFailurePolicy,
    public val onResponse: ResponsePolicy,
    /**
     * Android/iOS: the OS signal is authoritative, so hints are ignored by default. When `true`, a response
     * marks online, `MarkOffline` overrides the OS until the next OS event, and a re-check re-reads the
     * Android default network.
     */
    public val applyOnNativePlatforms: Boolean,
) {
    @ConnectivityDsl
    public class Builder internal constructor() {
        public var onRequestFailure: RequestFailurePolicy = RequestFailurePolicy.Recheck
        public var onResponse: ResponsePolicy = ResponsePolicy.MarkOnline
        public var applyOnNativePlatforms: Boolean = false

        internal fun build(): HintConfig = HintConfig(onRequestFailure, onResponse, applyOnNativePlatforms)
    }
}

public class IosConfig internal constructor(
    /** Deliver path updates on the main queue (default, cheap) or on a private serial queue. */
    public val useMainQueue: Boolean,
) {
    @ConnectivityDsl
    public class Builder internal constructor() {
        public var useMainQueue: Boolean = true

        internal fun build(): IosConfig = IosConfig(useMainQueue)
    }
}
