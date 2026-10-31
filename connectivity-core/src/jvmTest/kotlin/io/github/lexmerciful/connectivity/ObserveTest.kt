package io.github.lexmerciful.connectivity

import io.github.lexmerciful.connectivity.internal.DefaultConnectivityMonitor
import io.github.lexmerciful.connectivity.internal.FakeSource
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.fail

/** The callback API used from Swift and from code without coroutines, on real threads. */
class ObserveTest {

    private val source = FakeSource()
    private val monitor = DefaultConnectivityMonitor(ConnectivityConfig { }, source)

    @AfterTest
    fun tearDown() = monitor.close()

    /** The fake source drops events until the monitor subscribes, so emit until [queue] sees [expected]. */
    private fun <T> emitUntil(queue: LinkedBlockingQueue<T>, expected: (T) -> Boolean, emit: () -> Unit): T {
        repeat(200) {
            emit()
            while (true) {
                val value = queue.poll(25, TimeUnit.MILLISECONDS) ?: break
                if (expected(value)) return value
            }
        }
        fail("No matching value observed")
    }

    @Test
    fun observeDeliversTheCurrentValueThenChanges() {
        val values = LinkedBlockingQueue<Boolean>()
        val handle = monitor.observe { values += it }

        assertEquals(true, values.poll(5, TimeUnit.SECONDS), "the current value is delivered first")
        emitUntil(values, { !it }) { source.offline() }
        emitUntil(values, { it }) { source.online() }
        handle.close()
    }

    @Test
    fun observeStatusDeliversTheRichStatus() {
        val statuses = LinkedBlockingQueue<ConnectivityStatus>()
        val handle = monitor.observeStatus { statuses += it }

        val offline = emitUntil(statuses, { it is ConnectivityStatus.Disconnected }) { source.offline() }
        assertIs<ConnectivityStatus.Disconnected>(offline)
        handle.close()
    }

    @Test
    fun observeReconnectedFiresOnOfflineToOnline() {
        val values = LinkedBlockingQueue<Boolean>()
        val reconnects = LinkedBlockingQueue<Unit>()
        val connectedHandle = monitor.observe { values += it }
        val reconnectedHandle = monitor.observeReconnected { reconnects += Unit }

        // `reconnected` is not replayed and its handle subscribes asynchronously: flip until one arrives.
        val reconnect = (1..20).firstNotNullOfOrNull {
            emitUntil(values, { !it }) { source.offline() }
            emitUntil(values, { it }) { source.online() }
            reconnects.poll(250, TimeUnit.MILLISECONDS)
        }
        assertEquals(Unit, reconnect)
        connectedHandle.close()
        reconnectedHandle.close()
    }

    @Test
    fun closingTheHandleStopsCallbacks() {
        val values = LinkedBlockingQueue<Boolean>()
        val handle = monitor.observe { values += it }
        emitUntil(values, { !it }) { source.offline() }

        handle.close()
        Thread.sleep(100)
        values.clear()
        repeat(5) { source.online(); source.offline() }
        assertNull(values.poll(300, TimeUnit.MILLISECONDS), "no callback after close()")
    }
}
