package io.github.lexmerciful.connectivity.internal

import io.github.lexmerciful.connectivity.ConnectivityStatus
import io.github.lexmerciful.connectivity.DisconnectReason
import io.github.lexmerciful.connectivity.FailurePolicy
import io.github.lexmerciful.connectivity.Transport
import io.github.lexmerciful.connectivity.ValidationPolicy
import io.github.lexmerciful.connectivity.isConnected
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The validation state machine (docs/04 §3 and docs/05 §3). */
class StatusReducerTest {

    private fun reducer(policy: ValidationPolicy) = StatusReducer(policy, FailurePolicy.AssumeOnline)

    private fun StatusReducer.connected(event: RawStatus): Boolean? = reduce(event)?.isConnected

    private val wifi = "wifi"
    private val cell = "cell"

    @Test
    fun connectedToGoOfflineMatchesTheOriginalLiveData() {
        val r = reducer(ValidationPolicy.ConnectedToGoOffline)
        // The first reading only needs a connection.
        assertEquals(true, r.connected(RawStatus.Network(wifi, snapshot(validated = false), isInitial = true)))
        // Losing validation never reports offline.
        assertNull(r.reduce(RawStatus.Network(wifi, snapshot(internet = false, validated = false), isInitial = false)))
        assertNull(r.reduce(RawStatus.Blocked(wifi, true)))
        assertEquals(false, r.connected(RawStatus.Lost(wifi)))
        // Unvalidated callbacks do not bring it back; validated ones do.
        assertNull(r.reduce(RawStatus.Network(cell, snapshot(validated = false), isInitial = false)))
        assertNull(r.reduce(RawStatus.Network(cell, snapshot(captive = true, validated = false), isInitial = false)))
        assertEquals(true, r.connected(RawStatus.Network(cell, snapshot(), isInitial = false)))
    }

    @Test
    fun initialReadingWithoutANetworkIsOffline() {
        assertEquals(false, reducer(ValidationPolicy.ConnectedToGoOffline).connected(RawStatus.Lost(null)))
        assertEquals(
            false,
            reducer(ValidationPolicy.ConnectedToGoOffline)
                .connected(RawStatus.Network(wifi, snapshot(internet = false), isInitial = true)),
        )
    }

    @Test
    fun aLateOnLostOfThePreviousNetworkIsIgnored() {
        for (policy in ValidationPolicy.entries) {
            val r = reducer(policy)
            r.reduce(RawStatus.Network(wifi, snapshot(), isInitial = true))
            // Handover: cellular becomes the default, then the old Wi-Fi's onLost arrives late.
            r.reduce(RawStatus.Network(cell, snapshot(transport = Transport.Cellular), isInitial = false))
            assertNull(r.reduce(RawStatus.Lost(wifi)), "policy $policy: a stale onLost must not report offline")
            assertEquals(false, r.connected(RawStatus.Lost(cell)), "policy $policy")
        }
    }

    @Test
    fun requireValidated() {
        val r = reducer(ValidationPolicy.RequireValidated)
        assertEquals(
            ConnectivityStatus.Disconnected(DisconnectReason.NotValidated),
            r.reduce(RawStatus.Network(wifi, snapshot(validated = false), isInitial = true)),
        )
        assertEquals(true, r.connected(RawStatus.Network(wifi, snapshot(), isInitial = false)))
        assertEquals(
            ConnectivityStatus.Disconnected(DisconnectReason.NotValidated),
            r.reduce(RawStatus.Network(wifi, snapshot(captive = true), isInitial = false)),
        )
        r.reduce(RawStatus.Network(wifi, snapshot(), isInitial = false))
        assertEquals(ConnectivityStatus.Disconnected(DisconnectReason.Blocked), r.reduce(RawStatus.Blocked(wifi, true)))
        assertNull(r.reduce(RawStatus.Blocked(cell, false)), "blocked status of another network is ignored")
        assertEquals(true, r.connected(RawStatus.Blocked(wifi, false)))
        assertEquals(false, r.connected(RawStatus.Lost(wifi)))
    }

    @Test
    fun connectedOnly() {
        val r = reducer(ValidationPolicy.ConnectedOnly)
        assertEquals(true, r.connected(RawStatus.Network(wifi, snapshot(validated = false), isInitial = true)))
        r.reduce(RawStatus.Lost(wifi))
        assertEquals(true, r.connected(RawStatus.Network(cell, snapshot(validated = false), isInitial = false)))
        assertNull(r.reduce(RawStatus.Network(cell, snapshot(captive = true), isInitial = false)))
        assertNull(r.reduce(RawStatus.Blocked(cell, true)))
    }

    @Test
    fun connectedCarriesMetadata() {
        val status = reducer(ValidationPolicy.ConnectedToGoOffline).reduce(
            RawStatus.Network(cell, snapshot(transport = Transport.Cellular).copy(isMetered = true), isInitial = true),
        )
        assertEquals(
            ConnectivityStatus.Connected(Transport.Cellular, isMetered = true, isConstrained = false, isValidated = true),
            status,
        )
    }

    @Test
    fun iosPathMapping() {
        fun path(status: PathStatus) = RawStatus.Path(status, Transport.Wifi, isExpensive = false, isConstrained = true)
        for (policy in ValidationPolicy.entries) {
            val r = reducer(policy)
            assertEquals(true, r.connected(path(PathStatus.Satisfied)))
            assertEquals(policy == ValidationPolicy.ConnectedOnly, r.connected(path(PathStatus.RequiresConnection)))
            assertEquals(false, r.connected(path(PathStatus.Unsatisfied)))
        }
        assertEquals(
            ConnectivityStatus.Connected(Transport.Wifi, isMetered = false, isConstrained = true),
            reducer(ValidationPolicy.ConnectedToGoOffline).reduce(path(PathStatus.Satisfied)),
        )
    }

    @Test
    fun reachabilityAndFailurePolicies() {
        val r = reducer(ValidationPolicy.ConnectedToGoOffline)
        assertEquals(
            ConnectivityStatus.Disconnected(DisconnectReason.RequestFailed),
            r.reduce(RawStatus.Reachability(false, DisconnectReason.RequestFailed)),
        )
        assertEquals(ConnectivityStatus.Connected(), r.reduce(RawStatus.Reachability(true)))

        val cause = RuntimeException()
        assertEquals(ConnectivityStatus.Connected(), FailurePolicy.AssumeOnline.statusFor(cause))
        assertEquals(
            ConnectivityStatus.Disconnected(DisconnectReason.MonitorUnavailable),
            FailurePolicy.AssumeOffline.statusFor(cause),
        )
        assertEquals(ConnectivityStatus.MonitorError(cause), FailurePolicy.Error.statusFor(cause))
    }
}
