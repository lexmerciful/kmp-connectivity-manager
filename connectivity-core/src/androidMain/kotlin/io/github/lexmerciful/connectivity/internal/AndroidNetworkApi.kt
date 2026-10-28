package io.github.lexmerciful.connectivity.internal

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.github.lexmerciful.connectivity.Transport

/** The `ConnectivityManager` boundary; faked in tests. */
internal interface AndroidNetworkApi {

    /** [RawStatus.Network] (initial) for the active default network, or [RawStatus.Lost] with no id. */
    fun currentDefaultNetwork(): RawStatus

    /** Registers a default-network callback; `close()` unregisters it. May throw [RuntimeException]. */
    fun register(listener: Listener): AutoCloseable

    interface Listener {
        fun onCapabilities(networkId: String, snapshot: NetworkSnapshot)
        fun onLost(networkId: String)
        fun onBlocked(networkId: String, blocked: Boolean)
    }
}

internal class SystemAndroidNetworkApi(private val context: Context) : AndroidNetworkApi {

    private val manager: ConnectivityManager
        get() = checkNotNull(context.getSystemService(ConnectivityManager::class.java)) {
            "ConnectivityManager is not available"
        }

    override fun currentDefaultNetwork(): RawStatus {
        val cm = manager
        val network = cm.activeNetwork ?: return RawStatus.Lost(null)
        val caps = cm.getNetworkCapabilities(network) ?: return RawStatus.Lost(null)
        return RawStatus.Network(network.id, caps.toSnapshot(cm), isInitial = true)
    }

    override fun register(listener: AndroidNetworkApi.Listener): AutoCloseable {
        val cm = manager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                listener.onCapabilities(network.id, networkCapabilities.toSnapshot(cm))
            }

            override fun onLost(network: Network) {
                listener.onLost(network.id)
            }

            // API 29+; never called on older versions.
            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                listener.onBlocked(network.id, blocked)
            }
        }
        cm.registerDefaultNetworkCallback(callback)
        return AutoCloseable { cm.unregisterNetworkCallback(callback) }
    }

    private val Network.id: String get() = toString()

    private fun NetworkCapabilities.toSnapshot(cm: ConnectivityManager) = NetworkSnapshot(
        hasInternet = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        isValidated = hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        isCaptivePortal = hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
        transport = when {
            hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> Transport.Vpn
            hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.Wifi
            hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.Cellular
            hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.Ethernet
            else -> Transport.Other
        },
        isMetered = !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        isConstrained = runCatching {
            cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        }.getOrNull(),
    )
}
