package io.github.lexmerciful.connectivity.internal

import io.github.lexmerciful.connectivity.ConnectivityStatus
import io.github.lexmerciful.connectivity.DisconnectReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidConnectivitySourceTest {

    private class FakeApi(
        var current: RawStatus = RawStatus.Network("100", snapshot(), isInitial = true),
        val registerError: RuntimeException? = null,
    ) : AndroidNetworkApi {
        var listener: AndroidNetworkApi.Listener? = null
        var registrations = 0
        var unregistrations = 0

        override fun currentDefaultNetwork() = current

        override fun register(listener: AndroidNetworkApi.Listener): AutoCloseable {
            registerError?.let { throw it }
            registrations++
            this.listener = listener
            return AutoCloseable { unregistrations++; this.listener = null }
        }
    }

    @Test
    fun firstReadingThenCallbacksAndUnregisterOnCancel() = runTest {
        val api = FakeApi()
        val events = mutableListOf<RawStatus>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            AndroidConnectivitySource(api, applyHints = false).events(MutableSharedFlow()).toList(events)
        }
        runCurrent()
        assertEquals(1, api.registrations)
        api.listener!!.onLost("100")
        api.listener!!.onCapabilities("101", snapshot(validated = false))
        api.listener!!.onBlocked("101", true)
        runCurrent()
        assertEquals(
            listOf<RawStatus>(
                RawStatus.Network("100", snapshot(), isInitial = true),
                RawStatus.Lost("100"),
                RawStatus.Network("101", snapshot(validated = false), isInitial = false),
                RawStatus.Blocked("101", true),
            ),
            events,
        )
        job.cancel()
        runCurrent()
        assertEquals(1, api.unregistrations)
    }

    @Test
    fun registrationFailureIsReportedNotThrown() = runTest {
        val api = FakeApi(registerError = SecurityException("ACCESS_NETWORK_STATE missing"))
        val events = mutableListOf<RawStatus>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            AndroidConnectivitySource(api, applyHints = false).events(MutableSharedFlow()).toList(events)
        }
        runCurrent()
        assertIs<RawStatus.MonitorFailed>(events.last())
        assertNull(api.listener)
    }

    @Test
    fun hintsAreIgnoredByDefault() = runTest {
        val hints = MutableSharedFlow<Hint>(extraBufferCapacity = 4)
        val events = mutableListOf<RawStatus>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            AndroidConnectivitySource(FakeApi(), applyHints = false).events(hints).toList(events)
        }
        runCurrent()
        hints.emit(Hint.MarkOffline)
        runCurrent()
        assertEquals(1, events.size)
    }

    @Test
    fun hintsWhenEnabled() = runTest {
        val api = FakeApi()
        val hints = MutableSharedFlow<Hint>(extraBufferCapacity = 4)
        val events = mutableListOf<RawStatus>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            AndroidConnectivitySource(api, applyHints = true).events(hints).toList(events)
        }
        runCurrent()
        hints.emit(Hint.MarkOffline); runCurrent()
        api.current = RawStatus.Lost(null)
        hints.emit(Hint.Recheck); runCurrent()
        hints.emit(Hint.Reachable); runCurrent()
        assertEquals(
            listOf<RawStatus>(
                RawStatus.Reachability(false, DisconnectReason.RequestFailed),
                RawStatus.Lost(null),
                RawStatus.Reachability(true),
            ),
            events.drop(1),
        )
    }

    @Test
    fun endToEndHandoverDoesNotFlashOffline() = runTest {
        val api = FakeApi()
        val monitor = backgroundScope.monitor(AndroidConnectivitySource(api, applyHints = false))
        val values = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { monitor.isConnected.toList(values) }
        runCurrent()
        // Wi-Fi → cellular: the new default arrives first, then the old network's late onLost.
        api.listener!!.onCapabilities("200", snapshot())
        api.listener!!.onLost("100")
        runCurrent()
        assertEquals(listOf(true), values)
        assertIs<ConnectivityStatus.Connected>(monitor.status.value)
    }
}
