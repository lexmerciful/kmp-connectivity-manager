package io.github.lexmerciful.connectivity.config

import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How long a probe-based monitor (Desktop, Web with the probe on) waits before the next check.
 *
 * A hint from the app's HTTP layer can always cut the wait short.
 */
public sealed interface Schedule {

    /** Default. One interval while online, a shorter one while offline. */
    public class Adaptive(
        public val online: Duration = 30.seconds,
        public val offline: Duration = 3.seconds,
    ) : Schedule

    /** The same interval whatever the state. */
    public class Fixed(public val interval: Duration) : Schedule

    /**
     * [online] while online; while offline, starts at [initial] and multiplies by [factor] after each
     * failed check, capped at [max]. [jitter] (0.0..1.0) randomises each offline delay by ± that fraction.
     */
    public class Backoff(
        public val online: Duration = 30.seconds,
        public val initial: Duration = 1.seconds,
        public val factor: Double = 2.0,
        public val max: Duration = 30.seconds,
        public val jitter: Double = 0.1,
    ) : Schedule

    /** Fully custom: [next] returns the delay before the next check. */
    public class Custom(public val next: NextCheck) : Schedule

    public fun interface NextCheck {
        /**
         * @param isOnline the state currently reported.
         * @param consecutiveFailures failed checks since the last success (0 while online).
         */
        public fun delay(isOnline: Boolean, consecutiveFailures: Int): Duration
    }
}

internal fun Schedule.validate(name: String) {
    when (this) {
        is Schedule.Adaptive -> {
            requirePositive(online, "$name.online")
            requirePositive(offline, "$name.offline")
        }
        is Schedule.Fixed -> requirePositive(interval, "$name.interval")
        is Schedule.Backoff -> {
            requirePositive(online, "$name.online")
            requirePositive(initial, "$name.initial")
            requirePositive(max, "$name.max")
            require(factor >= 1.0 && factor.isFinite()) { "$name.factor must be a finite number >= 1.0, was $factor" }
            require(max >= initial) { "$name.max ($max) must be >= $name.initial ($initial)" }
            require(jitter in 0.0..1.0) { "$name.jitter must be in 0.0..1.0, was $jitter" }
        }
        is Schedule.Custom -> Unit
    }
}

/** The delay before the next check. Always positive, so a custom schedule cannot spin the loop. */
internal fun Schedule.nextDelay(isOnline: Boolean, consecutiveFailures: Int, random: Random): Duration {
    val delay = when (this) {
        is Schedule.Adaptive -> if (isOnline) online else offline
        is Schedule.Fixed -> interval
        is Schedule.Backoff -> if (isOnline) {
            online
        } else {
            val exponent = (consecutiveFailures - 1).coerceIn(0, 62)
            val base = (initial * factor.pow(exponent)).coerceAtMost(max)
            if (jitter == 0.0) base else base * (1.0 + jitter * (random.nextDouble() * 2.0 - 1.0))
        }
        is Schedule.Custom -> next.delay(isOnline, consecutiveFailures)
    }
    return delay.coerceAtLeast(MIN_DELAY)
}

private val MIN_DELAY: Duration = Duration.parse("100ms")

internal fun requirePositive(value: Duration, name: String) {
    require(value.isPositive() && value.isFinite()) { "$name must be a positive, finite duration, was $value" }
}

internal fun requireNonNegative(value: Duration, name: String) {
    require(!value.isNegative() && value.isFinite()) { "$name must be a non-negative, finite duration, was $value" }
}
