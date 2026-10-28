package io.github.lexmerciful.connectivity.internal

import io.github.lexmerciful.connectivity.ConnectivityConfig
import io.github.lexmerciful.connectivity.DisconnectReason
import io.github.lexmerciful.connectivity.config.WebConfig
import io.github.lexmerciful.connectivity.config.WebProbeConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class WebConnectivitySourceTest {

    private class FakeBrowser(var isOnlineNow: Boolean = true, var hidden: Boolean = false) : BrowserApi {
        var onlineListener: ((Boolean) -> Unit)? = null
        var visibilityListener: ((Boolean) -> Unit)? = null
        val probeTimes = mutableListOf<Long>()
        var probeResult = true
        lateinit var clock: () -> Long

        override val isOnline get() = isOnlineNow
        override val isHidden get() = hidden

        override fun onOnlineChange(listener: (Boolean) -> Unit): AutoCloseable {
            onlineListener = listener
            return AutoCloseable { onlineListener = null }
        }

        override fun onVisibilityChange(listener: (Boolean) -> Unit): AutoCloseable {
            visibilityListener = listener
            return AutoCloseable { visibilityListener = null }
        }

        override suspend fun probe(config: WebProbeConfig): Boolean {
            probeTimes += clock()
            return probeResult
        }

        fun goOnline(online: Boolean) {
            isOnlineNow = online
            onlineListener?.invoke(online)
        }

        fun setHidden(value: Boolean) {
            hidden = value
            visibilityListener?.invoke(value)
        }
    }

    private fun webConfig(block: WebConfig.Builder.() -> Unit = {}): WebConfig = ConnectivityConfig { web(block) }.web

    private fun TestScope.start(browser: FakeBrowser, config: WebConfig, hints: MutableSharedFlow<Hint> = MutableSharedFlow()): Pair<MutableList<RawStatus>, kotlinx.coroutines.Job> {
        browser.clock = { currentTime }
        val events = mutableListOf<RawStatus>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            WebConnectivitySource(config, browser, wallClockMillis = { currentTime }).events(hints).toList(events)
        }
        runCurrent()
        return events to job
    }

    private val List<RawStatus>.reachable get() = filterIsInstance<RawStatus.Reachability>().map { it.reachable }

    @Test
    fun navigatorEventsWithoutProbe() = runTest {
        val browser = FakeBrowser(isOnlineNow = false)
        val (events, job) = start(browser, webConfig())
        browser.goOnline(true)
        browser.goOnline(false)
        runCurrent()
        assertEquals(listOf(false, true, false), events.reachable)
        assertEquals(emptyList(), browser.probeTimes, "zero traffic without the probe")
        job.cancel()
        runCurrent()
        assertEquals(null, browser.onlineListener, "listeners are removed on cancel")
    }

    @Test
    fun hintsWithoutProbe() = runTest {
        val browser = FakeBrowser()
        val hints = MutableSharedFlow<Hint>(extraBufferCapacity = 4)
        val (events, _) = start(browser, webConfig(), hints)
        hints.emit(Hint.MarkOffline); runCurrent()
        hints.emit(Hint.Recheck); runCurrent()
        assertEquals(listOf(true, false, true), events.reachable)
        assertEquals(DisconnectReason.RequestFailed, (events[1] as RawStatus.Reachability).reason)
    }

    @Test
    fun probeScheduleOfflineEventAndOnlineRecheck() = runTest {
        val browser = FakeBrowser()
        val (events, _) = start(browser, webConfig { probe { enabled = true; url = "/health" } })
        assertEquals(listOf(0L), browser.probeTimes)
        advanceTimeBy(60_001.milliseconds)
        assertEquals(listOf(0L, 60_000L), browser.probeTimes)

        browser.goOnline(false)
        runCurrent()
        assertEquals(false, events.reachable.last(), "offline event reports at once")
        advanceTimeBy(5_001.milliseconds)
        assertEquals(listOf(0L, 60_000L), browser.probeTimes, "no fetch while navigator is offline")

        browser.goOnline(true)
        runCurrent()
        assertEquals(3, browser.probeTimes.size, "online event re-checks immediately")
        assertEquals(true, events.reachable.last())
    }

    @Test
    fun failedProbeIsProbeFailed() = runTest {
        val browser = FakeBrowser().apply { probeResult = false }
        val (events, _) = start(browser, webConfig { probe { enabled = true; url = "/health" } })
        assertEquals(RawStatus.Reachability(false, DisconnectReason.ProbeFailed), events.single())
    }

    @Test
    fun probePausesWhileHidden() = runTest {
        val browser = FakeBrowser(hidden = true)
        val (events, _) = start(browser, webConfig { probe { enabled = true; url = "/health" } })
        assertEquals(listOf(true), events.reachable, "navigator reading while hidden")
        advanceTimeBy(10.seconds)
        assertEquals(emptyList(), browser.probeTimes)
        browser.setHidden(false)
        runCurrent()
        assertEquals(listOf(10_000L), browser.probeTimes)
        browser.setHidden(true)
        advanceTimeBy(5.minutes())
        assertEquals(1, browser.probeTimes.size)
    }

    private fun Int.minutes() = (this * 60).seconds
}
