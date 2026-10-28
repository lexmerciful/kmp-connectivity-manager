package io.github.lexmerciful.connectivity

import io.github.lexmerciful.connectivity.config.HostPort
import io.github.lexmerciful.connectivity.config.ProbeStrategy
import io.github.lexmerciful.connectivity.config.ProbeTargets
import io.github.lexmerciful.connectivity.config.Schedule
import io.github.lexmerciful.connectivity.config.nextDelay
import io.github.lexmerciful.connectivity.config.requireProbeConfigured
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ConnectivityConfigTest {

    private fun assertInvalid(expectedInMessage: String, block: ConnectivityConfig.Builder.() -> Unit) {
        val error = assertFailsWith<IllegalArgumentException> { ConnectivityConfig(block) }
        assertContains(error.message.orEmpty(), expectedInMessage)
    }

    @Test
    fun defaultsAreTheReferenceBehaviour() {
        val config = ConnectivityConfig()
        assertEquals(ValidationPolicy.ConnectedToGoOffline, config.validation)
        assertEquals(InitialState.AssumeOnline, config.initialState)
        assertEquals(FailurePolicy.AssumeOnline, config.onMonitorFailure)
        assertEquals(ReconnectedPolicy.OnResumeIfWasOffline, config.reconnectedPolicy)
        assertEquals(5.seconds, config.stopTimeout)
        assertEquals(Duration.ZERO, config.debounce.offline)
        assertEquals(RequestFailurePolicy.Recheck, config.hints.onRequestFailure)
        assertEquals(ResponsePolicy.MarkOnline, config.hints.onResponse)
        assertEquals(false, config.hints.applyOnNativePlatforms)
        assertEquals(ProbeStrategy.Tcp, config.desktop.strategy)
        assertEquals(emptyList(), config.desktop.tcp.targets, "no third-party target by default")
        assertEquals(2.seconds, config.desktop.tcp.connectTimeout)
        assertEquals(7.seconds, config.desktop.checkBound)
        assertEquals(false, config.web.probe.enabled)
    }

    @Test
    fun invalidValuesFailFastWithTheOptionName() {
        assertInvalid("stopTimeout") { stopTimeout = (-1).seconds }
        assertInvalid("debounce.offline") { debounce { offline = (-1).seconds } }
        assertInvalid("desktop.checkBound") { desktop { checkBound = Duration.ZERO } }
        assertInvalid("desktop.tcp.connectTimeout") { desktop { checkBound = 1.seconds; tcp { connectTimeout = 2.seconds } } }
        assertInvalid("desktop.confirmOfflineAfter") { desktop { confirmOfflineAfter = 0 } }
        assertInvalid("desktop.schedule.offline") { desktop { schedule = Schedule.Adaptive(offline = Duration.ZERO) } }
        assertInvalid("desktop.schedule.factor") { desktop { schedule = Schedule.Backoff(factor = 0.5) } }
        assertInvalid("desktop.schedule.max") { desktop { schedule = Schedule.Backoff(initial = 10.seconds, max = 1.seconds) } }
        assertInvalid("desktop.schedule.jitter") { desktop { schedule = Schedule.Backoff(jitter = 2.0) } }
        assertInvalid("port") { desktop { tcp { targets = listOf(HostPort("1.1.1.1", 0)) } } }
        assertInvalid("blank host") { desktop { tcp { targets = listOf(HostPort(" ")) } } }
        assertInvalid("ftp") { desktop { http { url = "ftp://example.com" } } }
        assertInvalid("desktop.http.method") { desktop { http { method = "POST" } } }
        assertInvalid("desktop.http.expectedStatus") { desktop { http { expectedStatus = 600..700 } } }
        assertInvalid("web.probe.timeout") { web { probe { timeout = Duration.ZERO } } }
    }

    @Test
    fun desktopProbeNeedsExplicitTargets() {
        val error = assertFailsWith<IllegalArgumentException> { ConnectivityConfig().desktop.requireProbeConfigured() }
        assertContains(error.message.orEmpty(), "ProbeTargets.PublicResolvers")

        ConnectivityConfig { desktop { tcp { targets = ProbeTargets.PublicResolvers } } }.desktop.requireProbeConfigured()
        ConnectivityConfig { desktop { strategy = ProbeStrategy.InterfacesOnly } }.desktop.requireProbeConfigured()
        ConnectivityConfig { desktop { strategy = ProbeStrategy.HintsOnly } }.desktop.requireProbeConfigured()
        assertFailsWith<IllegalArgumentException> {
            ConnectivityConfig { desktop { strategy = ProbeStrategy.Http } }.desktop.requireProbeConfigured()
        }
        ConnectivityConfig {
            desktop {
                strategy = ProbeStrategy.Http
                http { url = ProbeTargets.Generate204Url }
            }
        }.desktop.requireProbeConfigured()
    }

    @Test
    fun probeTargetsFromUrl() {
        assertEquals(HostPort("api.example.com", 443), ProbeTargets.fromUrl("https://api.example.com/v1?x=1"))
        assertEquals(HostPort("api.example.com", 80), ProbeTargets.fromUrl("http://api.example.com"))
        assertEquals(HostPort("api.example.com", 8443), ProbeTargets.fromUrl("HTTPS://user@api.example.com:8443/"))
        assertEquals(HostPort("::1", 8080), ProbeTargets.fromUrl("http://[::1]:8080/health"))
        assertFailsWith<IllegalArgumentException> { ProbeTargets.fromUrl("api.example.com") }
        assertFailsWith<IllegalArgumentException> { ProbeTargets.fromUrl("https://:443") }
        assertFailsWith<IllegalArgumentException> { ProbeTargets.fromUrl("https://host:99999") }
        assertEquals("[2606:4700:4700::1111]:443", ProbeTargets.PublicResolversV6.first().toString())
    }

    @Test
    fun scheduleDelays() {
        val random = Random(0)
        val adaptive = Schedule.Adaptive()
        assertEquals(30.seconds, adaptive.nextDelay(isOnline = true, consecutiveFailures = 0, random))
        assertEquals(3.seconds, adaptive.nextDelay(isOnline = false, consecutiveFailures = 4, random))

        val backoff = Schedule.Backoff(initial = 1.seconds, factor = 2.0, max = 8.seconds, jitter = 0.0)
        assertEquals(listOf(1, 2, 4, 8, 8).map { it.seconds }, (1..5).map { backoff.nextDelay(false, it, random) })
        assertEquals(8.seconds, backoff.nextDelay(false, 10_000, random), "no overflow for huge failure counts")

        val jittered = Schedule.Backoff(initial = 10.seconds, max = 10.seconds, jitter = 0.1)
        repeat(100) {
            val delay = jittered.nextDelay(false, 1, random)
            assertTrue(delay in 9.seconds..11.seconds, "jitter out of range: $delay")
        }

        val spinning = Schedule.Custom { _, _ -> Duration.ZERO }
        assertEquals(100.milliseconds, spinning.nextDelay(true, 0, random), "a custom schedule cannot spin the loop")
    }
}
