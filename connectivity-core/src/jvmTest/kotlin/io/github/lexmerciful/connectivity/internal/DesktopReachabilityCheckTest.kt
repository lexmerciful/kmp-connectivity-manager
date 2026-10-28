package io.github.lexmerciful.connectivity.internal

import com.sun.net.httpserver.HttpServer
import io.github.lexmerciful.connectivity.ConnectivityConfig
import io.github.lexmerciful.connectivity.ConnectivityMonitor
import io.github.lexmerciful.connectivity.DisconnectReason
import io.github.lexmerciful.connectivity.config.DesktopConfig
import io.github.lexmerciful.connectivity.config.HostPort
import io.github.lexmerciful.connectivity.config.HttpProbeConfig
import io.github.lexmerciful.connectivity.config.InterfaceInfo
import io.github.lexmerciful.connectivity.config.ProbeOrder
import io.github.lexmerciful.connectivity.config.ProbeStrategy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopReachabilityCheckTest {

    private class FakeNetwork(
        var interfaces: List<InterfaceInfo>? = listOf(usableInterface),
        val connect: suspend (HostPort) -> Boolean = { true },
    ) : DesktopNetwork {
        val connectAttempts = mutableListOf<HostPort>()
        override fun interfaces() = interfaces
        override suspend fun connect(target: HostPort, timeout: Duration): Boolean {
            synchronized(connectAttempts) { connectAttempts += target }
            return connect(target)
        }
        override suspend fun httpReachable(config: HttpProbeConfig) = true
        override suspend fun resolves(hostname: String) = true
    }

    private fun desktop(block: DesktopConfig.Builder.() -> Unit): DesktopConfig =
        ConnectivityConfig { desktop(block) }.desktop

    private val targets = listOf(HostPort("1.1.1.1"), HostPort("8.8.8.8"), HostPort("api.example.com"))

    @Test
    fun noUsableInterfaceIsOfflineWithoutTraffic() = runTest {
        val network = FakeNetwork(interfaces = listOf(InterfaceInfo("vEthernet", "Hyper-V", true, false, true, true)))
        val check = DesktopReachabilityCheck(desktop { tcp { this.targets = this@DesktopReachabilityCheckTest.targets } }, network, StandardTestDispatcher(testScheduler))
        assertEquals(RawStatus.Reachability(false, DisconnectReason.NoNetwork), check.check())
        assertEquals(emptyList(), network.connectAttempts)
    }

    @Test
    fun unreadableInterfacesProbeAnyway() = runTest {
        for (interfaces in listOf(null, emptyList<InterfaceInfo>())) {
            val network = FakeNetwork(interfaces = interfaces)
            val check = DesktopReachabilityCheck(desktop { tcp { this.targets = this@DesktopReachabilityCheckTest.targets } }, network, StandardTestDispatcher(testScheduler))
            assertEquals(RawStatus.Reachability(true), check.check())
        }
    }

    @Test
    fun sequentialStopsAtTheFirstSuccess() = runTest {
        val network = FakeNetwork(connect = { it.host == "8.8.8.8" })
        val check = DesktopReachabilityCheck(desktop { tcp { this.targets = this@DesktopReachabilityCheckTest.targets } }, network, StandardTestDispatcher(testScheduler))
        assertEquals(true, check.check().reachable)
        assertEquals(targets.take(2), network.connectAttempts)
    }

    @Test
    fun allTargetsFailingIsProbeFailed() = runTest {
        val network = FakeNetwork(connect = { false })
        val check = DesktopReachabilityCheck(desktop { tcp { this.targets = this@DesktopReachabilityCheckTest.targets } }, network, StandardTestDispatcher(testScheduler))
        assertEquals(RawStatus.Reachability(false, DisconnectReason.ProbeFailed), check.check())
        assertEquals(targets, network.connectAttempts)
    }

    @Test
    fun theCheckIsBoundedEvenWhenTheProbeNeverReturns() = runTest {
        val network = FakeNetwork(connect = { awaitCancellation() })
        val check = DesktopReachabilityCheck(
            desktop { checkBound = 7.seconds; tcp { this.targets = this@DesktopReachabilityCheckTest.targets } },
            network,
            StandardTestDispatcher(testScheduler),
        )
        assertEquals(false, check.check().reachable)
        assertEquals(7_000L, currentTime)
    }

    @Test
    fun parallelFirstSuccessWinsWithoutWaitingForTheOthers() = runTest {
        val network = FakeNetwork(connect = { if (it.host == "8.8.8.8") { delay(100); true } else awaitCancellation() })
        val check = DesktopReachabilityCheck(
            desktop { tcp { this.targets = this@DesktopReachabilityCheckTest.targets; order = ProbeOrder.Parallel } },
            network,
            StandardTestDispatcher(testScheduler),
        )
        assertEquals(true, check.check().reachable)
        assertEquals(100L, currentTime)
    }

    @Test
    fun aHostWhoseLookupIsStillStuckIsSkipped() = runTest {
        val stuck = CompletableDeferred<Boolean>()
        // Like a blocked InetAddress lookup: cancellation does not stop it.
        val network = FakeNetwork(connect = { withContext(NonCancellable) { stuck.await() } })
        val check = DesktopReachabilityCheck(
            desktop { checkBound = 2.seconds; tcp { this.targets = listOf(HostPort("api.example.com")) } },
            network,
            StandardTestDispatcher(testScheduler),
        )
        assertEquals(false, check.check().reachable) // bound hit; the "lookup" keeps running
        assertEquals(false, check.check().reachable)
        assertEquals(1, network.connectAttempts.size, "no second lookup while the first is stuck")
        stuck.complete(false)
        runCurrent()
        check.check()
        assertEquals(2, network.connectAttempts.size)
    }

    @Test
    fun interfacesOnlyNeverConnects() = runTest {
        val network = FakeNetwork()
        val check = DesktopReachabilityCheck(desktop { strategy = ProbeStrategy.InterfacesOnly }, network, StandardTestDispatcher(testScheduler))
        assertEquals(true, check.check().reachable)
        assertEquals(emptyList(), network.connectAttempts)
    }

    @Test
    fun aThrowingCustomCheckPropagatesToTheFailurePolicy() = runTest {
        val check = DesktopReachabilityCheck(
            desktop { strategy = ProbeStrategy.Custom { error("custom bug") } },
            FakeNetwork(),
            StandardTestDispatcher(testScheduler),
        )
        assertFailsWith<IllegalStateException> { check.check() }
    }

    @Test
    fun hintsOnlyReactsToHintsAndNeverProbes() = runTest {
        var checks = 0
        val source = DesktopConnectivitySource(desktop { strategy = ProbeStrategy.HintsOnly }, { checks++; RawStatus.Reachability(true) })
        val hints = MutableSharedFlow<Hint>(extraBufferCapacity = 4)
        val events = mutableListOf<RawStatus>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.events(hints).toList(events) }
        hints.emit(Hint.MarkOffline)
        hints.emit(Hint.Recheck)
        hints.emit(Hint.Reachable)
        runCurrent()
        assertEquals(
            listOf<RawStatus>(RawStatus.Reachability(false, DisconnectReason.RequestFailed), RawStatus.Reachability(true)),
            events,
        )
        assertEquals(0, checks)
    }

    @Test
    fun theFactoryRefusesAProbeWithoutTargets() {
        val error = assertFailsWith<IllegalArgumentException> { ConnectivityMonitor() }
        assertContains(error.message.orEmpty(), "never contacts third-party hosts")
        ConnectivityMonitor { desktop { strategy = ProbeStrategy.HintsOnly } }.close()
    }

    @Test
    fun httpProbeAgainstALocalServer() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/204") { it.sendResponseHeaders(204, -1); it.close() }
        server.createContext("/portal") { it.sendResponseHeaders(200, -1); it.close() }
        server.createContext("/redirect") { it.responseHeaders.add("Location", "/204"); it.sendResponseHeaders(302, -1); it.close() }
        server.createContext("/slow") { Thread.sleep(3_000); it.sendResponseHeaders(204, -1); it.close() }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            suspend fun probe(path: String, statuses: IntRange = 204..204, wait: Duration = 5.seconds) =
                SystemDesktopNetwork().httpReachable(
                    desktop {
                        checkBound = 10.seconds
                        http {
                            url = base + path
                            expectedStatus = statuses
                            useSystemProxy = false
                            timeout = wait
                        }
                    }.http,
                )
            assertTrue(probe("/204"), "204")
            assertEquals(false, probe("/portal"), "a captive portal answering 200 is not reachable in strict mode")
            assertEquals(false, probe("/redirect"), "redirects are not followed")
            assertTrue(probe("/redirect", 200..399), "redirect accepted by a lenient range")
            assertEquals(false, probe("/slow", wait = 500.milliseconds), "read timeout")
            assertEquals(false, SystemDesktopNetwork().httpReachable(desktop { http { url = "http://127.0.0.1:1/" ; timeout = 1.seconds } }.http))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun tcpProbeAgainstALocalSocket() = runBlocking {
        java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            val network = SystemDesktopNetwork()
            assertTrue(network.connect(HostPort("127.0.0.1", server.localPort), 1.seconds))
        }
        assertEquals(false, SystemDesktopNetwork().connect(HostPort("unresolvable.invalid", 443), 1.seconds))
    }

    @Test
    fun endToEndMonitorOnARealSocket() = runBlocking {
        java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress()).use { server ->
            val monitor = ConnectivityMonitor {
                desktop {
                    interfacePreCheck = false
                    tcp { targets = listOf(HostPort("127.0.0.1", server.localPort)) }
                }
            }
            val reading = monitor.status.first { it is io.github.lexmerciful.connectivity.ConnectivityStatus.Connected }
            assertTrue(reading is io.github.lexmerciful.connectivity.ConnectivityStatus.Connected)
            monitor.close()
        }
    }
}

private val usableInterface = InterfaceInfo("eth0", "Ethernet", isUp = true, isLoopback = false, isVirtual = false, hasRoutableAddress = true)
