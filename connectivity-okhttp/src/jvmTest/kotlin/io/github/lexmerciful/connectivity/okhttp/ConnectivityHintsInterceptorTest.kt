package io.github.lexmerciful.connectivity.okhttp

import io.github.lexmerciful.connectivity.ConnectivityMonitor
import io.github.lexmerciful.connectivity.ConnectivityStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConnectivityHintsInterceptorTest {

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

    private fun client(monitor: ConnectivityMonitor, ignore: (Request) -> Boolean = { false }) = OkHttpClient.Builder()
        .addInterceptor(ConnectivityHintsInterceptor(monitor, ignore))
        .connectTimeout(1, TimeUnit.SECONDS)
        .build()

    @Test
    fun anyResponseReportsReachable() {
        val monitor = RecordingMonitor()
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(503).build())
            server.start()
            client(monitor).newCall(Request.Builder().url(server.url("/")).build()).execute().close()
        }
        assertEquals(1, monitor.reachable)
        assertEquals(0, monitor.rechecks)
    }

    @Test
    fun aConnectionFailureRequestsARecheck() {
        val monitor = RecordingMonitor()
        val closedPort = ServerSocket(0).use { it.localPort }
        assertFailsWith<IOException> {
            client(monitor).newCall(Request.Builder().url("http://127.0.0.1:$closedPort/").build()).execute()
        }
        assertEquals(1, monitor.rechecks)
        assertEquals(0, monitor.reachable)
    }

    @Test
    fun aCancelledCallIsNotReported() {
        val monitor = RecordingMonitor()
        MockWebServer().use { server ->
            server.start()
            val call = client(monitor).newCall(Request.Builder().url(server.url("/")).build())
            call.cancel()
            assertFailsWith<IOException> { call.execute() }
        }
        assertEquals(0, monitor.rechecks)
    }

    @Test
    fun ignoredRequestsAreNotReported() {
        val monitor = RecordingMonitor()
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(200).build())
            server.start()
            client(monitor, ignore = { true }).newCall(Request.Builder().url(server.url("/")).build()).execute().close()
        }
        assertEquals(0, monitor.reachable)
    }
}
