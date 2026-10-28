package io.github.lexmerciful.connectivity.ktor

import io.github.lexmerciful.connectivity.ConnectivityMonitor
import io.github.lexmerciful.connectivity.ConnectivityStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConnectivityHintsTest {

    private class RecordingMonitor : ConnectivityMonitor {
        var reachable = 0
        var rechecks = 0
        override val isConnected: StateFlow<Boolean> = MutableStateFlow(true)
        override val status: StateFlow<ConnectivityStatus> = MutableStateFlow(ConnectivityStatus.Connected())
        override val reconnected: Flow<Unit> = emptyFlow()
        override fun reportReachable() { reachable++ }
        override fun requestRecheck() { rechecks++ }
        override fun close() = Unit
    }

    private fun client(
        monitor: ConnectivityMonitor,
        expectSuccess: Boolean = false,
        configure: ConnectivityHintsConfig.() -> Unit = {},
        handler: suspend () -> HttpStatusCode,
    ) = HttpClient(MockEngine { respond("", handler()) }) {
        this.expectSuccess = expectSuccess
        install(ConnectivityHints) {
            this.monitor = monitor
            configure()
        }
    }

    @Test
    fun anyResponseReportsReachable() = runTest {
        val monitor = RecordingMonitor()
        client(monitor) { HttpStatusCode.InternalServerError }.get("https://api.example.com/")
        client(monitor) { HttpStatusCode.OK }.get("https://api.example.com/")
        assertEquals(2, monitor.reachable)
        assertEquals(0, monitor.rechecks)
    }

    @Test
    fun aResponseExceptionStillProvesReachability() = runTest {
        val monitor = RecordingMonitor()
        assertFailsWith<Exception> { client(monitor, expectSuccess = true) { HttpStatusCode.NotFound }.get("https://x/") }
        assertTrue(monitor.reachable >= 1)
        assertEquals(0, monitor.rechecks)
    }

    @Test
    fun anIoFailureRequestsARecheck() = runTest {
        val monitor = RecordingMonitor()
        assertFailsWith<IOException> { client(monitor) { throw IOException("connection reset") }.get("https://x/") }
        assertEquals(1, monitor.rechecks)
        assertEquals(0, monitor.reachable)
    }

    @Test
    fun cancellationIsNeverAConnectivityFailure() = runTest {
        val monitor = RecordingMonitor()
        assertFailsWith<CancellationException> { client(monitor) { throw CancellationException("left the screen") }.get("https://x/") }
        assertEquals(0, monitor.rechecks)
    }

    @Test
    fun otherExceptionsAreNotReportedByDefault() = runTest {
        val monitor = RecordingMonitor()
        assertFailsWith<IllegalStateException> { client(monitor) { error("bug") }.get("https://x/") }
        assertEquals(0, monitor.rechecks)
    }

    @Test
    fun ignoreFilterAndToggles() = runTest {
        val monitor = RecordingMonitor()
        client(monitor, configure = { ignore = { it.url.host == "analytics.example.com" } }) { HttpStatusCode.OK }
            .get("https://analytics.example.com/")
        assertEquals(0, monitor.reachable)

        assertFailsWith<IOException> {
            client(monitor, configure = { reportFailures = false }) { throw IOException() }.get("https://x/")
        }
        assertEquals(0, monitor.rechecks)
    }

    @Test
    fun defaultFailureDetectionFollowsCauses() {
        assertTrue(isDefaultConnectionFailure(IllegalStateException("wrapped", IOException("dns"))))
        assertEquals(false, isDefaultConnectionFailure(IllegalArgumentException()))
    }
}
