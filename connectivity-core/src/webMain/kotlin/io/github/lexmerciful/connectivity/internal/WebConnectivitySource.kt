package io.github.lexmerciful.connectivity.internal

import io.github.lexmerciful.connectivity.DisconnectReason
import io.github.lexmerciful.connectivity.config.WebConfig
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Web source. Without the probe: `navigator.onLine` plus `online`/`offline` events (offline is reliable,
 * online only means "some network"). With the probe: a [ProbeLoop] that skips the fetch while
 * `navigator.onLine` is false, re-checks on `online`, reports offline at once on `offline`, and pauses
 * while the tab is hidden.
 */
internal class WebConnectivitySource(
    private val config: WebConfig,
    private val browser: BrowserApi,
    private val wallClockMillis: () -> Long = ::systemWallClockMillis,
) : PlatformConnectivitySource {

    override fun events(hints: Flow<Hint>): Flow<RawStatus> =
        if (config.probe.enabled) probeEvents(hints) else browserEvents(hints)

    private fun browserEvents(hints: Flow<Hint>): Flow<RawStatus> = callbackFlow {
        trySend(navigatorReading())
        val registration = browser.onOnlineChange { online ->
            trySend(RawStatus.Reachability(online, DisconnectReason.NoNetwork))
        }
        launch { hints.collect { hint -> trySend(hint.toOverride() ?: navigatorReading()) } }
        awaitClose { registration.close() }
    }.buffer(Channel.UNLIMITED)

    private fun probeEvents(hints: Flow<Hint>): Flow<RawStatus> = channelFlow {
        val probe = config.probe
        val hidden = MutableStateFlow(browser.isHidden)
        val browserTriggers = Channel<ProbeTrigger>(Channel.UNLIMITED)
        val onlineRegistration = browser.onOnlineChange { online ->
            browserTriggers.trySend(
                if (online) ProbeTrigger.Recheck
                else ProbeTrigger.Report(reachable = false, reason = DisconnectReason.NoNetwork),
            )
        }
        val visibilityRegistration =
            if (probe.pauseWhenHidden) browser.onVisibilityChange { hidden.value = it } else null
        try {
            // Hidden at start: no probe yet, but the navigator reading is free.
            if (probe.pauseWhenHidden && hidden.value) send(navigatorReading())
            ProbeLoop(
                schedule = probe.schedule,
                confirmOfflineAfter = probe.confirmOfflineAfter,
                wallClockMillis = wallClockMillis,
                paused = if (probe.pauseWhenHidden) hidden else null,
            ) {
                when {
                    probe.onlyWhenNavigatorOnline && !browser.isOnline ->
                        RawStatus.Reachability(false, DisconnectReason.NoNetwork)
                    browser.probe(probe) -> RawStatus.Reachability(true)
                    else -> RawStatus.Reachability(false, DisconnectReason.ProbeFailed)
                }
            }.events(merge(hints.map { it.toTrigger() }, browserTriggers.receiveAsFlow()))
                .collect { send(it) }
        } finally {
            onlineRegistration.close()
            visibilityRegistration?.close()
        }
    }

    private fun navigatorReading() = RawStatus.Reachability(browser.isOnline, DisconnectReason.NoNetwork)
}

@OptIn(ExperimentalTime::class)
private fun systemWallClockMillis(): Long = Clock.System.now().toEpochMilliseconds()
