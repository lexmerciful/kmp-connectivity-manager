package io.github.lexmerciful.connectivity.config

import io.github.lexmerciful.connectivity.ConnectivityDsl
import io.github.lexmerciful.connectivity.internal.parseHttpUrl
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Desktop (JVM) settings. Ignored on other platforms, so shared code can configure everything once.
 *
 * The JVM has no connectivity callback, so the monitor combines a zero-traffic interface pre-check,
 * a bounded reachability probe on a [schedule], and hints from the app's HTTP layer.
 */
public class DesktopConfig internal constructor(
    /** How reachability is checked. Default [ProbeStrategy.Tcp]. */
    public val strategy: ProbeStrategy,
    /** Report offline with no traffic when no usable network interface exists. Default `true`. */
    public val interfacePreCheck: Boolean,
    /** Which interfaces count as usable for the pre-check. */
    public val interfaceFilter: InterfaceFilter,
    public val tcp: TcpProbeConfig,
    public val http: HttpProbeConfig,
    public val dns: DnsProbeConfig,
    /** Upper bound for one whole check; hitting it counts as offline. Default 7 s. */
    public val checkBound: Duration,
    /** When to check. Default [Schedule.Adaptive] (30 s online, 3 s offline). */
    public val schedule: Schedule,
    /** Consecutive failed checks before offline is reported. Default 1. */
    public val confirmOfflineAfter: Int,
) {

    @ConnectivityDsl
    public class Builder internal constructor() {
        public var strategy: ProbeStrategy = ProbeStrategy.Tcp
        public var interfacePreCheck: Boolean = true
        public var interfaceFilter: InterfaceFilter = InterfaceFilter.Default
        public var checkBound: Duration = 7.seconds
        public var schedule: Schedule = Schedule.Adaptive()
        public var confirmOfflineAfter: Int = 1

        private val tcp = TcpProbeConfig.Builder()
        private val http = HttpProbeConfig.Builder()
        private val dns = DnsProbeConfig.Builder()

        /** TCP probe settings, used by [ProbeStrategy.Tcp]. */
        public fun tcp(block: TcpProbeConfig.Builder.() -> Unit) {
            tcp.block()
        }

        /** HTTP probe settings, used by [ProbeStrategy.Http]. */
        public fun http(block: HttpProbeConfig.Builder.() -> Unit) {
            http.block()
        }

        /** DNS probe settings, used by [ProbeStrategy.Dns]. */
        public fun dns(block: DnsProbeConfig.Builder.() -> Unit) {
            dns.block()
        }

        internal fun build(): DesktopConfig {
            val tcp = tcp.build()
            val http = http.build()
            val dns = dns.build()
            requirePositive(checkBound, "desktop.checkBound")
            schedule.validate("desktop.schedule")
            require(confirmOfflineAfter >= 1) { "desktop.confirmOfflineAfter must be >= 1, was $confirmOfflineAfter" }
            when (strategy) {
                ProbeStrategy.Tcp -> require(checkBound >= tcp.connectTimeout) {
                    "desktop.checkBound ($checkBound) must be >= desktop.tcp.connectTimeout (${tcp.connectTimeout})"
                }
                ProbeStrategy.Http -> require(checkBound >= http.timeout) {
                    "desktop.checkBound ($checkBound) must be >= desktop.http.timeout (${http.timeout})"
                }
                else -> Unit
            }
            return DesktopConfig(
                strategy, interfacePreCheck, interfaceFilter, tcp, http, dns, checkBound, schedule, confirmOfflineAfter,
            )
        }
    }
}

/** How the desktop monitor checks reachability. */
public sealed interface ProbeStrategy {
    /** Default. TCP handshake to [TcpProbeConfig.targets]; no payload is sent. */
    public data object Tcp : ProbeStrategy

    /** HTTP request to [HttpProbeConfig.url]. Works behind HTTP-only proxies and can detect captive portals. */
    public data object Http : ProbeStrategy

    /** DNS resolution of [DnsProbeConfig.hostname] only. Cheapest, but OS caches can answer while offline. */
    public data object Dns : ProbeStrategy

    /** Zero traffic: the interface check only. Virtual adapters can keep it "online" while offline. */
    public data object InterfacesOnly : ProbeStrategy

    /** Zero traffic and no schedule: only [io.github.lexmerciful.connectivity.ConnectivityMonitor] hints change the state. */
    public data object HintsOnly : ProbeStrategy

    /** Your own check; return `true` when the internet is reachable. Runs on an IO thread. */
    public class Custom(public val check: suspend () -> Boolean) : ProbeStrategy
}

/** Order of the TCP targets. */
public enum class ProbeOrder {
    /** Default. One target after another, first success wins: fewest packets. */
    Sequential,

    /** All targets at once, first success wins and cancels the rest: fastest answer. */
    Parallel,
}

public class TcpProbeConfig internal constructor(
    public val targets: List<HostPort>,
    public val order: ProbeOrder,
    public val connectTimeout: Duration,
) {
    @ConnectivityDsl
    public class Builder internal constructor() {
        /**
         * Required for [ProbeStrategy.Tcp]; empty by default so no third-party host is contacted implicitly.
         * E.g. `ProbeTargets.PublicResolvers + ProbeTargets.fromUrl(apiBaseUrl)`.
         */
        public var targets: List<HostPort> = emptyList()
        public var order: ProbeOrder = ProbeOrder.Sequential

        /** Timeout of each connect. Default 2 s. */
        public var connectTimeout: Duration = 2.seconds

        internal fun build(): TcpProbeConfig {
            requirePositive(connectTimeout, "desktop.tcp.connectTimeout")
            targets.forEach { target ->
                require(target.host.isNotBlank()) { "desktop.tcp.targets contains a blank host" }
                require(target.port in 1..65535) { "desktop.tcp.targets: port of $target must be in 1..65535" }
            }
            return TcpProbeConfig(targets.toList(), order, connectTimeout)
        }
    }
}

public class HttpProbeConfig internal constructor(
    public val url: String?,
    public val method: String,
    public val expectedStatus: IntRange,
    public val useSystemProxy: Boolean,
    public val timeout: Duration,
    public val headers: Map<String, String>,
) {
    @ConnectivityDsl
    public class Builder internal constructor() {
        /** Required for [ProbeStrategy.Http]: your health endpoint or [ProbeTargets.Generate204Url]. */
        public var url: String? = null

        /** `HEAD` (default), `GET` or `OPTIONS`. */
        public var method: String = "HEAD"

        /** Statuses that count as reachable. Default 200..399. Use 204..204 with generate_204 to detect captive portals. */
        public var expectedStatus: IntRange = 200..399

        /** Route the probe through the system proxy (`ProxySelector.getDefault()`). Default `true`. */
        public var useSystemProxy: Boolean = true

        /** Connect and read timeout. Default 3 s. */
        public var timeout: Duration = 3.seconds

        /** Extra request headers, e.g. an API key for your health endpoint. */
        public var headers: Map<String, String> = emptyMap()

        internal fun build(): HttpProbeConfig {
            url?.let { parseHttpUrl(it) }
            require(method.uppercase() in ALLOWED_METHODS) {
                "desktop.http.method must be one of $ALLOWED_METHODS, was $method"
            }
            require(!expectedStatus.isEmpty() && expectedStatus.first >= 100 && expectedStatus.last <= 599) {
                "desktop.http.expectedStatus must be a non-empty range within 100..599, was $expectedStatus"
            }
            requirePositive(timeout, "desktop.http.timeout")
            return HttpProbeConfig(url, method.uppercase(), expectedStatus, useSystemProxy, timeout, headers.toMap())
        }

        private companion object {
            val ALLOWED_METHODS = setOf("HEAD", "GET", "OPTIONS")
        }
    }
}

public class DnsProbeConfig internal constructor(public val hostname: String?) {
    @ConnectivityDsl
    public class Builder internal constructor() {
        /** Required for [ProbeStrategy.Dns]. */
        public var hostname: String? = null

        internal fun build(): DnsProbeConfig {
            hostname?.let { require(it.isNotBlank()) { "desktop.dns.hostname must not be blank" } }
            return DnsProbeConfig(hostname)
        }
    }
}

/** A network interface as seen by the desktop pre-check. */
public class InterfaceInfo(
    public val name: String,
    public val displayName: String,
    public val isUp: Boolean,
    public val isLoopback: Boolean,
    public val isVirtual: Boolean,
    /** Has an address that is neither loopback nor link-local. */
    public val hasRoutableAddress: Boolean,
) {
    override fun toString(): String =
        "InterfaceInfo(name=$name, up=$isUp, loopback=$isLoopback, virtual=$isVirtual, routable=$hasRoutableAddress)"
}

/** Decides whether an interface counts as usable for the desktop pre-check. */
public fun interface InterfaceFilter {
    public fun isUsable(info: InterfaceInfo): Boolean

    public companion object {
        /** Up, not loopback, not virtual, and has a routable address. */
        public val Default: InterfaceFilter = InterfaceFilter {
            it.isUp && !it.isLoopback && !it.isVirtual && it.hasRoutableAddress
        }
    }
}

internal fun DesktopConfig.requireProbeConfigured() {
    when (strategy) {
        ProbeStrategy.Tcp -> require(tcp.targets.isNotEmpty()) {
            "Desktop connectivity probe has no targets. The library never contacts third-party hosts implicitly. " +
                "Set one of: desktop { tcp { targets = ProbeTargets.PublicResolvers } } (Cloudflare + Google), " +
                "desktop { tcp { targets = listOf(ProbeTargets.fromUrl(\"https://api.example.com\")) } }, " +
                "desktop { strategy = ProbeStrategy.Http; http { url = ProbeTargets.Generate204Url } }, " +
                "or strategy = ProbeStrategy.InterfacesOnly / ProbeStrategy.HintsOnly for zero traffic."
        }
        ProbeStrategy.Http -> require(http.url != null) {
            "desktop.strategy is ProbeStrategy.Http but desktop.http.url is not set. " +
                "Use your health endpoint or ProbeTargets.Generate204Url."
        }
        ProbeStrategy.Dns -> require(dns.hostname != null) {
            "desktop.strategy is ProbeStrategy.Dns but desktop.dns.hostname is not set."
        }
        else -> Unit
    }
}
