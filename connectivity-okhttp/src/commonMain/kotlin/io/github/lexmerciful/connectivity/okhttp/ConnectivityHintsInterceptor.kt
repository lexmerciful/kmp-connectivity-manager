package io.github.lexmerciful.connectivity.okhttp

import io.github.lexmerciful.connectivity.ConnectivityMonitor
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * OkHttp application interceptor that feeds request outcomes into a [ConnectivityMonitor]:
 * any response → [ConnectivityMonitor.reportReachable]; an [IOException] → [ConnectivityMonitor.requestRecheck].
 * Cancelled calls are never reported. What each hint does is configured on the monitor (`hints { ... }`).
 *
 * ```
 * OkHttpClient.Builder().addInterceptor(ConnectivityHintsInterceptor(monitor)).build()
 * ```
 *
 * @param ignore requests to leave out, e.g. analytics.
 */
public class ConnectivityHintsInterceptor @JvmOverloads constructor(
    private val monitor: ConnectivityMonitor,
    private val ignore: (Request) -> Boolean = { false },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (ignore(request)) return chain.proceed(request)
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            if (!chain.call().isCanceled()) monitor.requestRecheck()
            throw e
        }
        monitor.reportReachable()
        return response
    }
}
