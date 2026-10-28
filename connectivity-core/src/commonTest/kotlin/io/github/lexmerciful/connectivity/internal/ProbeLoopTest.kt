package io.github.lexmerciful.connectivity.internal

import io.github.lexmerciful.connectivity.DisconnectReason
import io.github.lexmerciful.connectivity.config.Schedule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ProbeLoopTest {

    private class Harness(scope: TestScope, results: List<Boolean>, loop: (check: suspend () -> RawStatus.Reachability) -> ProbeLoop) {
        val checkTimes = mutableListOf<Long>()
        val events = mutableListOf<RawStatus>()
        val triggers = MutableSharedFlow<ProbeTrigger>(extraBufferCapacity = 8)
        private var index = 0

        init {
            val probeLoop = loop {
                checkTimes += scope.currentTime
                val reachable = results.getOrElse(index++) { results.last() }
                if (reachable) RawStatus.Reachability(true) else RawStatus.Reachability(false, DisconnectReason.ProbeFailed)
            }
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
                probeLoop.events(triggers).toList(events)
            }
        }

        val reachability: List<Boolean> get() = events.filterIsInstance<RawStatus.Reachability>().map { it.reachable }
    }

    private fun TestScope.harness(
        vararg results: Boolean,
        schedule: Schedule = Schedule.Adaptive(online = 30.seconds, offline = 3.seconds),
        confirmOfflineAfter: Int = 1,
        wallClock: () -> Long = { testScheduler.currentTime },
        paused: MutableStateFlow<Boolean>? = null,
    ) = Harness(this, results.toList()) { check ->
        ProbeLoop(schedule, confirmOfflineAfter, wallClock, paused = paused, check = check)
    }.also { runCurrent() }

    @Test
    fun offlineIsRecheckedEveryThreeSeconds() = runTest {
        val h = harness(false, false, true)
        advanceTimeBy(6_001.milliseconds)
        assertEquals(listOf(0L, 3_000L, 6_000L), h.checkTimes)
        assertEquals(listOf(false, false, true), h.reachability)
    }

    @Test
    fun onlineIsRecheckedEveryThirtySeconds() = runTest {
        val h = harness(true)
        advanceTimeBy(30_001.milliseconds)
        assertEquals(listOf(0L, 30_000L), h.checkTimes)
    }

    @Test
    fun recheckRunsACheckImmediately() = runTest {
        val h = harness(true)
        advanceTimeBy(1.seconds)
        h.triggers.emit(ProbeTrigger.Recheck)
        runCurrent()
        assertEquals(listOf(0L, 1_000L), h.checkTimes)
    }

    @Test
    fun aReportAppliesWithoutAProbeAndResetsTheOnlineTimer() = runTest {
        val h = harness(false, true)
        advanceTimeBy(1.seconds)
        h.triggers.emit(ProbeTrigger.Report(reachable = true, reason = DisconnectReason.ProbeFailed))
        runCurrent()
        assertEquals(listOf(false, true), h.reachability)
        assertEquals(listOf(0L), h.checkTimes, "no probe for a response hint")
        advanceTimeBy(30_001.milliseconds)
        assertEquals(listOf(0L, 31_000L), h.checkTimes, "the online interval restarts from the hint")
    }

    @Test
    fun markOfflineSwitchesToTheOfflineInterval() = runTest {
        val h = harness(true)
        h.triggers.emit(ProbeTrigger.Report(reachable = false, reason = DisconnectReason.RequestFailed))
        runCurrent()
        assertEquals(DisconnectReason.RequestFailed, (h.events.last() as RawStatus.Reachability).reason)
        advanceTimeBy(3_001.milliseconds)
        assertEquals(listOf(0L, 3_000L), h.checkTimes)
        assertEquals(listOf(true, false, true), h.reachability)
    }

    @Test
    fun aThrowingCheckIsReportedAsMonitorFailure() = runTest {
        val events = mutableListOf<RawStatus>()
        val loop = ProbeLoop(Schedule.Fixed(1.seconds), 1, { testScheduler.currentTime }) { error("boom") }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { loop.events(MutableSharedFlow()).toList(events) }
        runCurrent()
        assertIs<RawStatus.MonitorFailed>(events.single())
    }

    @Test
    fun confirmOfflineAfterTwoFailuresUsesTheOfflineIntervalMeanwhile() = runTest {
        val h = harness(true, false, false, true, confirmOfflineAfter = 2)
        advanceTimeBy(30_001.milliseconds)
        assertEquals(listOf(true), h.reachability, "one failure is not reported")
        advanceTimeBy(3.seconds)
        assertEquals(listOf(0L, 30_000L, 33_000L), h.checkTimes)
        assertEquals(listOf(true, false), h.reachability)
        advanceTimeBy(3.seconds)
        assertEquals(listOf(true, false, true), h.reachability)
    }

    @Test
    fun backoffSchedule() = runTest {
        val h = harness(
            false, false, false, false, false, true,
            schedule = Schedule.Backoff(initial = 1.seconds, factor = 2.0, max = 5.seconds, jitter = 0.0),
        )
        advanceTimeBy(60.seconds)
        // Delays: 1, 2, 4, 5 (capped), 5, then online 30 s.
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L, 12_000L, 17_000L, 47_000L), h.checkTimes)
    }

    @Test
    fun customSchedule() = runTest {
        val h = harness(false, false, schedule = Schedule.Custom { _, failures -> (failures * 100).milliseconds })
        advanceTimeBy(301.milliseconds)
        assertEquals(listOf(0L, 100L, 300L), h.checkTimes)
    }

    @Test
    fun pausedSkipsScheduledChecksAndChecksOnResume() = runTest {
        val paused = MutableStateFlow(true)
        val h = harness(true, paused = paused)
        advanceTimeBy(120.seconds)
        assertEquals(emptyList(), h.checkTimes, "no probe while hidden")
        paused.value = false
        runCurrent()
        assertEquals(listOf(120_000L), h.checkTimes)
        paused.value = true
        advanceTimeBy(60.seconds)
        assertEquals(listOf(120_000L), h.checkTimes)
    }

    @Test
    fun aWallClockJumpChecksImmediately() = runTest {
        var sleptMillis = 0L
        val h = harness(true, wallClock = { testScheduler.currentTime + sleptMillis })
        advanceTimeBy(2.seconds)
        sleptMillis = 3_600_000 // the machine slept for an hour
        advanceTimeBy(3_001.milliseconds) // end of the first 5 s slice
        assertEquals(listOf(0L, 5_000L), h.checkTimes)
    }
}
