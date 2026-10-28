package io.github.lexmerciful.connectivity.internal

import io.github.lexmerciful.connectivity.DisconnectReason
import io.github.lexmerciful.connectivity.config.DesktopConfig
import io.github.lexmerciful.connectivity.config.HostPort
import io.github.lexmerciful.connectivity.config.ProbeOrder
import io.github.lexmerciful.connectivity.config.ProbeStrategy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * One desktop reachability check:
 * 1. interface pre-check (zero traffic): no usable interface → offline;
 * 2. the probe of the configured [ProbeStrategy], bounded by [DesktopConfig.checkBound].
 *
 * The probe runs on its own scope and the caller only waits for the bound, because DNS lookups made by
 * `InetSocketAddress` / `InetAddress` cannot be interrupted. A host target whose previous lookup is still
 * stuck is skipped, so stuck lookups cannot pile up threads.
 */
internal class DesktopReachabilityCheck(
    private val config: DesktopConfig,
    private val network: DesktopNetwork = SystemDesktopNetwork(),
    dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(PROBE_PARALLELISM),
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val hostsInFlight: MutableSet<HostPort> = ConcurrentHashMap.newKeySet()

    suspend fun check(): RawStatus.Reachability {
        if (config.interfacePreCheck || config.strategy == ProbeStrategy.InterfacesOnly) {
            // null = the interface list is unavailable: unknown, so probe anyway (never a false offline).
            val usable = network.interfaces()?.takeIf { it.isNotEmpty() }?.any(config.interfaceFilter::isUsable)
            if (usable == false) return RawStatus.Reachability(false, DisconnectReason.NoNetwork)
            if (config.strategy == ProbeStrategy.InterfacesOnly) return RawStatus.Reachability(true)
        }
        val probe = scope.async { probe() }
        val reachable = withTimeoutOrNull(config.checkBound) { probe.await() }
        if (reachable == null) probe.cancel()
        return if (reachable == true) RawStatus.Reachability(true)
        else RawStatus.Reachability(false, DisconnectReason.ProbeFailed)
    }

    private suspend fun probe(): Boolean = when (val strategy = config.strategy) {
        ProbeStrategy.Tcp -> when (config.tcp.order) {
            ProbeOrder.Sequential -> config.tcp.targets.any { target ->
                currentCoroutineContext().ensureActive()
                connect(target)
            }
            ProbeOrder.Parallel -> connectAny(config.tcp.targets)
        }
        ProbeStrategy.Http -> network.httpReachable(config.http)
        ProbeStrategy.Dns -> network.resolves(checkNotNull(config.dns.hostname))
        is ProbeStrategy.Custom -> strategy.check()
        ProbeStrategy.InterfacesOnly, ProbeStrategy.HintsOnly -> true
    }

    /** First success wins and cancels the rest. The others run on [scope], so a blocked one never delays the answer. */
    private suspend fun connectAny(targets: List<HostPort>): Boolean {
        val results = Channel<Boolean>(Channel.UNLIMITED)
        val jobs: List<Job> = targets.map { target -> scope.launch { results.send(connect(target)) } }
        try {
            repeat(targets.size) { if (results.receive()) return true }
            return false
        } finally {
            jobs.forEach(Job::cancel)
        }
    }

    private suspend fun connect(target: HostPort): Boolean {
        if (target.host.isIpLiteral()) return network.connect(target, config.tcp.connectTimeout)
        if (!hostsInFlight.add(target)) return false // the previous DNS lookup for this host is still stuck
        return try {
            network.connect(target, config.tcp.connectTimeout)
        } finally {
            hostsInFlight.remove(target)
        }
    }

    private companion object {
        const val PROBE_PARALLELISM = 4
    }
}

private val IPV4_LITERAL = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

internal fun String.isIpLiteral(): Boolean = ':' in this || IPV4_LITERAL.matches(this)
