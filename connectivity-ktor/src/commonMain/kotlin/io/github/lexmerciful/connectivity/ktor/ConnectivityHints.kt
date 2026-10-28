package io.github.lexmerciful.connectivity.ktor

import io.github.lexmerciful.connectivity.ConnectivityDsl
import io.github.lexmerciful.connectivity.ConnectivityMonitor
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException

/**
 * Feeds real request outcomes into a [ConnectivityMonitor]:
 * - any response (whatever its status) → [ConnectivityMonitor.reportReachable];
 * - a connection failure (I/O error, DNS failure, timeout) → [ConnectivityMonitor.requestRecheck].
 *
 * What the monitor does with each hint is configured on the monitor (`hints { ... }`): by default a
 * response marks online and a failure triggers a fresh check, never an instant "offline". Cancelled
 * requests (the user left the screen) are never reported. Install it in common code: on Android and iOS
 * the monitor ignores hints unless `hints { applyOnNativePlatforms = true }`.
 *
 * ```
 * HttpClient(engine) {
 *     install(ConnectivityHints) { monitor = connectivityMonitor }
 * }
 * ```
 */
public val ConnectivityHints: ClientPlugin<ConnectivityHintsConfig> =
    createClientPlugin("ConnectivityHints", ::ConnectivityHintsConfig) {
        val config = pluginConfig
        val monitor = requireNotNull(config.monitor) { "ConnectivityHints: set `monitor` when installing the plugin" }

        on(Send) { request ->
            if (config.ignore(request)) return@on proceed(request)
            try {
                proceed(request).also { if (config.reportResponses) monitor.reportReachable() }
            } catch (cause: Throwable) {
                when {
                    cause is CancellationException -> Unit
                    cause is ResponseException -> if (config.reportResponses) monitor.reportReachable()
                    config.reportFailures && config.isConnectionFailure(cause) -> monitor.requestRecheck()
                }
                throw cause
            }
        }
    }

@ConnectivityDsl
public class ConnectivityHintsConfig {
    /** Required: the app's monitor. */
    public var monitor: ConnectivityMonitor? = null

    /** Report responses. Default `true`. */
    public var reportResponses: Boolean = true

    /** Report connection failures. Default `true`. */
    public var reportFailures: Boolean = true

    /** Which exceptions count as connection failures. Default: I/O errors, DNS failures and timeouts. */
    public var isConnectionFailure: (Throwable) -> Boolean = ::isDefaultConnectionFailure

    /** Requests to leave out, e.g. `{ it.url.host == "analytics.example.com" }`. */
    public var ignore: (HttpRequestBuilder) -> Boolean = { false }
}

/** I/O errors, DNS failures and timeouts, including when wrapped in another exception. */
public fun isDefaultConnectionFailure(cause: Throwable): Boolean =
    generateSequence(cause) { it.cause }.take(MAX_CAUSE_DEPTH).any { it.isConnectionFailureItself() }

private fun Throwable.isConnectionFailureItself(): Boolean =
    this is IOException ||
        this is HttpRequestTimeoutException ||
        this is ConnectTimeoutException ||
        this is SocketTimeoutException ||
        // java.nio.channels.UnresolvedAddressException (CIO, DNS failure) is not an IOException.
        this::class.simpleName == "UnresolvedAddressException"

private const val MAX_CAUSE_DEPTH = 8
