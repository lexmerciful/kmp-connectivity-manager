package io.github.lexmerciful.connectivity.internal

import io.github.lexmerciful.connectivity.ConnectivityConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Hints are documented as cheap, thread-safe and never blocking: hammer them from many threads at once. */
class ConcurrentHintsTest {

    @Test
    fun hintsFromManyThreadsNeverBlockOrBreakTheMonitor() = runBlocking {
        val source = FakeSource()
        val monitor = DefaultConnectivityMonitor(ConnectivityConfig { }, source)
        val collector = launch { monitor.isConnected.collect { } }
        withTimeout(5.seconds) { while (source.activeRuns == 0) delay(10) }

        val threads = 8
        val callsPerThread = 20_000
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) { index ->
            pool.execute {
                start.await()
                repeat(callsPerThread) {
                    if ((it + index) % 2 == 0) monitor.reportReachable() else monitor.requestRecheck()
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS), "hint calls must never block")
        pool.shutdown()

        // The pipeline still follows the source afterwards.
        withTimeout(5.seconds) {
            while (monitor.isConnected.value) {
                source.offline()
                delay(10)
            }
        }
        withTimeout(5.seconds) { monitor.isConnected.first { !it } }
        assertTrue(source.receivedHints.isNotEmpty(), "hints reach the source")

        collector.cancel()
        monitor.close()
    }
}
