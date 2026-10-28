package io.github.lexmerciful.connectivity.internal

import io.github.lexmerciful.connectivity.config.HostPort
import io.github.lexmerciful.connectivity.config.HttpProbeConfig
import io.github.lexmerciful.connectivity.config.InterfaceInfo
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.Socket
import java.net.SocketException
import java.net.URI
import kotlin.time.Duration

/** The JVM system boundary of the desktop check; faked in tests. Calls may block (they run on IO threads). */
internal interface DesktopNetwork {
    /** `null` when the interface list cannot be read. */
    fun interfaces(): List<InterfaceInfo>?

    suspend fun connect(target: HostPort, timeout: Duration): Boolean

    suspend fun httpReachable(config: HttpProbeConfig): Boolean

    suspend fun resolves(hostname: String): Boolean
}

internal class SystemDesktopNetwork : DesktopNetwork {

    override fun interfaces(): List<InterfaceInfo>? = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().mapNotNull { ni ->
            try {
                InterfaceInfo(
                    name = ni.name,
                    displayName = ni.displayName ?: ni.name,
                    isUp = ni.isUp,
                    isLoopback = ni.isLoopback,
                    isVirtual = ni.isVirtual,
                    hasRoutableAddress = ni.inetAddresses.toList().any { !it.isLoopbackAddress && !it.isLinkLocalAddress },
                )
            } catch (_: SocketException) {
                null
            }
        }
    } catch (_: SocketException) {
        null
    } catch (_: SecurityException) {
        null
    }

    override suspend fun connect(target: HostPort, timeout: Duration): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(target.host, target.port), timeout.toTimeoutMillis()) }
        true
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    override suspend fun httpReachable(config: HttpProbeConfig): Boolean {
        val url = URI(checkNotNull(config.url)).toURL()
        val connection = try {
            (if (config.useSystemProxy) url.openConnection() else url.openConnection(Proxy.NO_PROXY)) as HttpURLConnection
        } catch (_: IOException) {
            return false
        }
        return try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = config.timeout.toTimeoutMillis()
            connection.readTimeout = config.timeout.toTimeoutMillis()
            connection.requestMethod = config.method
            connection.setRequestProperty("Cache-Control", "no-cache")
            config.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.responseCode in config.expectedStatus
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun resolves(hostname: String): Boolean = try {
        InetAddress.getAllByName(hostname).isNotEmpty()
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

private fun Duration.toTimeoutMillis(): Int = inWholeMilliseconds.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
