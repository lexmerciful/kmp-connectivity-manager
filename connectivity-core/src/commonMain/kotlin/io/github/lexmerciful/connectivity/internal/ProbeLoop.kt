package io.github.lexmerciful.connectivity.internal

import io.github.lexmerciful.connectivity.DisconnectReason
import io.github.lexmerciful.connectivity.config.Schedule
import io.github.lexmerciful.connectivity.config.nextDelay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Something that interrupts the probe schedule. */
internal sealed interface ProbeTrigger {
    /** Check now (a request failed, the browser fired `online`, ...). */
    data object Recheck : ProbeTrigger

    /** A definitive answer that needs no probe (a response arrived, `MarkOffline`, the browser fired `offline`). */
    data class Report(val reachable: Boolean, val reason: DisconnectReason) : ProbeTrigger

    /** Internal to [ProbeLoop]: the pause flag turned on while waiting. */
    data object Paused : ProbeTrigger
}

internal fun Hint.toTrigger(): ProbeTrigger = when (this) {
    Hint.Reachable -> ProbeTrigger.Report(reachable = true, reason = DisconnectReason.ProbeFailed)
    Hint.Recheck -> ProbeTrigger.Recheck
    Hint.MarkOffline -> ProbeTrigger.Report(reachable = false, reason = DisconnectReason.RequestFailed)
}

/**
 * The scheduling loop shared by the probe-based sources (desktop, web with the probe on):
 *
 * ```
 * check → report → wait(schedule) or a trigger → repeat
 * ```
 *
 * - A [ProbeTrigger.Report] applies immediately and restarts the wait (so app traffic resets the online timer).
 * - Offline is reported only after [confirmOfflineAfter] consecutive failed checks; while a failure is
 *   unconfirmed, the next check runs on the offline interval.
 * - A check that throws is reported as [RawStatus.MonitorFailed] (the pipeline applies the failure policy).
 * - While [paused] is `true` no scheduled check runs; a check runs as soon as it turns `false`.
 * - If the wall clock jumps past the monotonic wait (the machine slept), a check runs right away.
 */
internal class ProbeLoop(
    private val schedule: Schedule,
    private val confirmOfflineAfter: Int,
    private val wallClockMillis: () -> Long,
    private val random: Random = Random.Default,
    private val paused: StateFlow<Boolean>? = null,
    private val check: suspend () -> RawStatus.Reachability,
) {

    fun events(triggers: Flow<ProbeTrigger>): Flow<RawStatus> = channelFlow {
        val signals = Channel<ProbeTrigger>(Channel.CONFLATED)
        launch { triggers.collect { signals.trySend(it) } }

        var reportedOnline: Boolean? = null
        var failures = 0

        suspend fun probe() {
            val result = try {
                check()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                send(RawStatus.MonitorFailed(e))
                return
            }
            if (result.reachable) {
                failures = 0
                reportedOnline = true
                send(result)
            } else {
                failures++
                if (failures >= confirmOfflineAfter) {
                    reportedOnline = false
                    send(result)
                }
            }
        }

        suspend fun apply(report: ProbeTrigger.Report) {
            failures = if (report.reachable) 0 else maxOf(failures, confirmOfflineAfter)
            reportedOnline = report.reachable
            send(RawStatus.Reachability(report.reachable, report.reason))
        }

        if (paused?.value != true) probe()
        while (true) {
            val pausedFlow = paused
            val trigger = if (pausedFlow != null && pausedFlow.value) {
                awaitResumeOrSignal(pausedFlow, signals)
            } else {
                val online = reportedOnline != false && failures == 0
                awaitSignal(schedule.nextDelay(online, failures, random), signals, pausedFlow)
            }
            when (trigger) {
                null, ProbeTrigger.Recheck -> probe()
                is ProbeTrigger.Report -> apply(trigger)
                ProbeTrigger.Paused -> Unit // next iteration waits for the resume
            }
        }
    }

    /**
     * Waits up to [delay] for a signal; `null` = time to check (timeout or wake from sleep),
     * [ProbeTrigger.Paused] = [paused] turned on meanwhile.
     */
    private suspend fun awaitSignal(
        delay: Duration,
        signals: ReceiveChannel<ProbeTrigger>,
        paused: StateFlow<Boolean>?,
    ): ProbeTrigger? {
        var remaining = delay
        while (remaining.isPositive()) {
            val slice = minOf(remaining, WAKE_DETECTION_SLICE)
            val wallStart = wallClockMillis()
            val trigger = withTimeoutOrNull(slice) {
                if (paused == null) {
                    signals.receive()
                } else {
                    coroutineScope {
                        val pausing = async { paused.first { it }; ProbeTrigger.Paused }
                        try {
                            select {
                                pausing.onAwait { it }
                                signals.onReceive { it }
                            }
                        } finally {
                            pausing.cancel()
                        }
                    }
                }
            }
            if (trigger != null) return trigger
            val wallElapsed = (wallClockMillis() - wallStart).milliseconds
            if (wallElapsed > slice + WAKE_DETECTION_SLACK) return null
            remaining -= slice
        }
        return null
    }

    /** `null` = resumed. */
    private suspend fun awaitResumeOrSignal(paused: StateFlow<Boolean>, signals: ReceiveChannel<ProbeTrigger>): ProbeTrigger? =
        coroutineScope {
            val resumed = async { paused.first { !it } }
            try {
                select {
                    resumed.onAwait { null }
                    signals.onReceive { it }
                }
            } finally {
                resumed.cancel()
            }
        }

    private companion object {
        val WAKE_DETECTION_SLICE = 5.seconds
        val WAKE_DETECTION_SLACK = 5.seconds
    }
}
