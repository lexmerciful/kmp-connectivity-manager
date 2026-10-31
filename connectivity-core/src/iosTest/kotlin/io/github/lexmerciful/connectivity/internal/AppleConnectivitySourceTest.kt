package io.github.lexmerciful.connectivity.internal

import io.github.lexmerciful.connectivity.ConnectivityMonitor
import io.github.lexmerciful.connectivity.ConnectivityStatus
import io.github.lexmerciful.connectivity.InitialState
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * Runs the real `NWPathMonitor` on the simulator. Tests block the main thread, so the source uses its
 * own dispatch queue (`useMainQueue = false`) for the update handler.
 */
class AppleConnectivitySourceTest {

    @Test
    fun theMonitorReportsTheCurrentPathRightAway() = runBlocking<Unit> {
        val source = AppleConnectivitySource(useMainQueue = false, applyHints = false)
        val first = withTimeout(10.seconds) { source.events(emptyFlow()).first() }
        assertIs<RawStatus.Path>(first)
    }

    @Test
    fun eachRunCreatesAFreshMonitor() = runBlocking<Unit> {
        // A cancelled nw_path_monitor cannot restart; a second run must still report a path.
        val source = AppleConnectivitySource(useMainQueue = false, applyHints = false)
        repeat(3) {
            val reading = withTimeout(10.seconds) { source.events(emptyFlow()).first() }
            assertIs<RawStatus.Path>(reading)
        }
    }

    @Test
    fun theFactoryMonitorReachesAFirstReading() = runBlocking<Unit> {
        val monitor = ConnectivityMonitor {
            initialState = InitialState.Unknown
            ios { useMainQueue = false }
        }
        try {
            val reading = withTimeout(10.seconds) { monitor.status.first { it != ConnectivityStatus.Unknown } }
            // CI simulators share the host's network.
            assertIs<ConnectivityStatus.Connected>(reading)
        } finally {
            monitor.close()
        }
    }
}
