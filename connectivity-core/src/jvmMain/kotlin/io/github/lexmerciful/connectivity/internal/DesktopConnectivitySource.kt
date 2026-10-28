package io.github.lexmerciful.connectivity.internal

import io.github.lexmerciful.connectivity.config.DesktopConfig
import io.github.lexmerciful.connectivity.config.ProbeStrategy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlin.random.Random

/**
 * Desktop source: [ProbeLoop] around a reachability [check], driven by the schedule and hints.
 * With [ProbeStrategy.HintsOnly] there is no schedule: only reported responses and failures change the state.
 */
internal class DesktopConnectivitySource(
    private val config: DesktopConfig,
    private val check: suspend () -> RawStatus.Reachability,
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) : PlatformConnectivitySource {

    override fun events(hints: Flow<Hint>): Flow<RawStatus> =
        if (config.strategy == ProbeStrategy.HintsOnly) {
            hints.mapNotNull { it.toOverride() }
        } else {
            ProbeLoop(
                schedule = config.schedule,
                confirmOfflineAfter = config.confirmOfflineAfter,
                wallClockMillis = wallClockMillis,
                random = random,
                check = check,
            ).events(hints.map { it.toTrigger() })
        }
}
