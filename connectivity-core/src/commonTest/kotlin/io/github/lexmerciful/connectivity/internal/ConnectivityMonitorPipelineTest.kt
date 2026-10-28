package io.github.lexmerciful.connectivity.internal

import io.github.lexmerciful.connectivity.ConnectivityStatus
import io.github.lexmerciful.connectivity.DisconnectReason
import io.github.lexmerciful.connectivity.FailurePolicy
import io.github.lexmerciful.connectivity.InitialState
import io.github.lexmerciful.connectivity.ReconnectedPolicy
import io.github.lexmerciful.connectivity.RequestFailurePolicy
import io.github.lexmerciful.connectivity.ResponsePolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectivityMonitorPipelineTest {

    private fun TestScope.collectConnected(monitor: DefaultConnectivityMonitor): Pair<MutableList<Boolean>, Job> {
        val values = mutableListOf<Boolean>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { monitor.isConnected.toList(values) }
        runCurrent()
        return values to job
    }

    @Test
    fun startsConnectedBeforeTheFirstReading() = runTest {
        val monitor = backgroundScope.monitor(FakeSource())
        val (values, _) = collectConnected(monitor)
        assertEquals(listOf(true), values)
        assertEquals(ConnectivityStatus.Connected(), monitor.status.value)
    }

    @Test
    fun initialStateVariants() = runTest {
        assertEquals(false, backgroundScope.monitor(FakeSource()) { initialState = InitialState.AssumeOffline }.isConnected.value)
        val unknown = backgroundScope.monitor(FakeSource()) { initialState = InitialState.Unknown }
        assertEquals(ConnectivityStatus.Unknown, unknown.status.value)
        assertEquals(true, unknown.isConnected.value, "Unknown must read as connected")
    }

    @Test
    fun followsTheSourceWithoutRepeats() = runTest {
        val source = FakeSource()
        val (values, _) = collectConnected(backgroundScope.monitor(source))
        source.offline(); runCurrent()
        source.offline(); runCurrent()
        source.online(); runCurrent()
        source.online(); runCurrent()
        source.offline(); runCurrent()
        assertEquals(listOf(true, false, true, false), values)
    }

    @Test
    fun monitorFailureAssumeOnlineByDefault() = runTest {
        val source = FakeSource()
        val monitor = backgroundScope.monitor(source) { initialState = InitialState.AssumeOffline }
        collectConnected(monitor)
        source.emit(RawStatus.MonitorFailed(IllegalStateException("denied")))
        runCurrent()
        assertEquals(true, monitor.isConnected.value)
    }

    @Test
    fun monitorFailureAssumeOffline() = runTest {
        val source = FakeSource()
        val monitor = backgroundScope.monitor(source) { onMonitorFailure = FailurePolicy.AssumeOffline }
        collectConnected(monitor)
        source.emit(RawStatus.MonitorFailed(IllegalStateException()))
        runCurrent()
        assertEquals(ConnectivityStatus.Disconnected(DisconnectReason.MonitorUnavailable), monitor.status.value)
        assertEquals(false, monitor.isConnected.value)
    }

    @Test
    fun monitorFailureErrorStillReadsConnected() = runTest {
        val source = FakeSource()
        val monitor = backgroundScope.monitor(source) {
            onMonitorFailure = FailurePolicy.Error
            initialState = InitialState.AssumeOffline
        }
        collectConnected(monitor)
        val cause = IllegalStateException("boom")
        source.emit(RawStatus.MonitorFailed(cause))
        runCurrent()
        assertEquals(ConnectivityStatus.MonitorError(cause), monitor.status.value)
        assertEquals(true, monitor.isConnected.value)
    }

    @Test
    fun aCrashingSourceAppliesTheFailurePolicyInsteadOfPropagating() = runTest {
        val crashing = PlatformConnectivitySource {
            flow {
                emit(RawStatus.Reachability(false))
                delay(1.seconds)
                error("source bug")
            }
        }
        val monitor = backgroundScope.monitor(crashing)
        val (values, _) = collectConnected(monitor)
        advanceTimeBy(2.seconds); runCurrent()
        assertEquals(listOf(true, false, true), values)
    }

    @Test
    fun offlineDebounceHidesShortFlaps() = runTest {
        val source = FakeSource()
        val (values, _) = collectConnected(backgroundScope.monitor(source) { debounce { offline = 1.seconds } })
        source.online(); runCurrent()
        source.offline(); runCurrent()
        advanceTimeBy(500.milliseconds)
        source.online(); runCurrent()
        advanceTimeBy(5.seconds)
        assertEquals(listOf(true), values, "a 500 ms flap must not be shown")

        source.offline(); runCurrent()
        advanceTimeBy(999.milliseconds); runCurrent()
        assertEquals(listOf(true), values)
        advanceTimeBy(2.milliseconds); runCurrent()
        assertEquals(listOf(true, false), values)
    }

    @Test
    fun debounceDoesNotDelayTheFirstReading() = runTest {
        val source = FakeSource()
        val (values, _) = collectConnected(backgroundScope.monitor(source) { debounce { offline = 10.seconds } })
        source.offline(); runCurrent()
        assertEquals(listOf(true, false), values)
    }

    @Test
    fun onlineDebounce() = runTest {
        val source = FakeSource()
        val (values, _) = collectConnected(backgroundScope.monitor(source) { debounce { online = 2.seconds } })
        source.offline(); runCurrent()
        source.online(); runCurrent()
        advanceTimeBy(1.seconds); runCurrent()
        assertEquals(listOf(true, false), values)
        advanceTimeBy(1.seconds + 1.milliseconds); runCurrent()
        assertEquals(listOf(true, false, true), values)
    }

    @Test
    fun reconnectedFiresOncePerOfflineToOnlineButNotOnTheFirstReading() = runTest {
        val source = FakeSource()
        val monitor = backgroundScope.monitor(source) { initialState = InitialState.AssumeOffline }
        val events = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { monitor.reconnected.toList(events) }
        runCurrent()
        source.online(); runCurrent()
        assertEquals(0, events.size, "the first reading is not a reconnection")
        source.offline(); runCurrent()
        source.online(); runCurrent()
        source.online(); runCurrent()
        assertEquals(1, events.size)
        source.offline(); runCurrent()
        source.online(); runCurrent()
        assertEquals(2, events.size)
    }

    @Test
    fun reconnectedResumePolicies() = runTest {
        for (policy in ReconnectedPolicy.entries) {
            val source = FakeSource()
            val monitor = backgroundScope.monitor(source) {
                reconnectedPolicy = policy
                stopTimeout = 1.seconds
            }
            val events = mutableListOf<Unit>()
            val (_, run1) = collectConnected(monitor)
            source.offline(); runCurrent()
            run1.cancel()
            advanceTimeBy(2.seconds); runCurrent()
            assertEquals(0, source.activeRuns, "monitoring must stop after stopTimeout")

            val eventsJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { monitor.reconnected.toList(events) }
            runCurrent()
            source.online(); runCurrent()
            val expected = if (policy == ReconnectedPolicy.OnResumeIfWasOffline) 1 else 0
            assertEquals(expected, events.size, "policy $policy")
            eventsJob.cancel()
        }
    }

    @Test
    fun oneSourceRunForManyCollectorsAndStopAfterTimeout() = runTest {
        val source = FakeSource()
        val monitor = backgroundScope.monitor(source)
        val jobs = List(100) { collectConnected(monitor).second } + List(10) {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { monitor.status.collect { } }
        }
        runCurrent()
        assertEquals(1, source.starts)
        assertEquals(1, source.activeRuns)

        jobs.forEach(Job::cancel)
        advanceTimeBy(4.9.seconds); runCurrent()
        assertEquals(1, source.activeRuns, "still running inside the 5 s grace period")
        advanceTimeBy(200.milliseconds); runCurrent()
        assertEquals(0, source.activeRuns)

        collectConnected(monitor)
        assertEquals(2, source.starts, "a new collector restarts monitoring")
    }

    @Test
    fun infiniteStopTimeoutNeverStops() = runTest {
        val source = FakeSource()
        val monitor = backgroundScope.monitor(source) { stopTimeout = Duration.INFINITE }
        collectConnected(monitor).second.cancel()
        advanceTimeBy(1_000.seconds); runCurrent()
        assertEquals(1, source.activeRuns)
    }

    @Test
    fun hintsAreDroppedWhileNobodyCollects() = runTest {
        val source = FakeSource()
        val monitor = backgroundScope.monitor(source)
        monitor.reportReachable()
        monitor.requestRecheck()
        collectConnected(monitor)
        assertEquals(emptyList(), source.receivedHints)

        monitor.reportReachable(); runCurrent()
        monitor.requestRecheck(); runCurrent()
        assertEquals(listOf(Hint.Reachable, Hint.Recheck), source.receivedHints)
    }

    @Test
    fun hintPolicies() = runTest {
        val source = FakeSource()
        val monitor = backgroundScope.monitor(source) {
            hints {
                onRequestFailure = RequestFailurePolicy.MarkOffline
                onResponse = ResponsePolicy.Ignore
            }
        }
        collectConnected(monitor)
        monitor.reportReachable(); runCurrent()
        monitor.requestRecheck(); runCurrent()
        assertEquals(listOf(Hint.MarkOffline), source.receivedHints)

        val ignoring = FakeSource()
        val ignoringMonitor = backgroundScope.monitor(ignoring) { hints { onRequestFailure = RequestFailurePolicy.Ignore } }
        collectConnected(ignoringMonitor)
        ignoringMonitor.requestRecheck(); runCurrent()
        assertEquals(emptyList(), ignoring.receivedHints)
    }

    @Test
    fun closeStopsTheSourceAndHintsBecomeNoOps() = runTest {
        val source = FakeSource()
        val monitor = backgroundScope.monitor(source)
        collectConnected(monitor)
        assertEquals(1, source.activeRuns)
        monitor.close()
        runCurrent()
        assertEquals(0, source.activeRuns)
        monitor.reportReachable()
        monitor.requestRecheck()
        assertIs<ConnectivityStatus.Connected>(monitor.status.value)
    }
}
