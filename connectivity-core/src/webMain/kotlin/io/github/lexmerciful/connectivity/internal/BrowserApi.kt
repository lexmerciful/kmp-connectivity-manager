package io.github.lexmerciful.connectivity.internal

import io.github.lexmerciful.connectivity.config.FetchMode
import io.github.lexmerciful.connectivity.config.WebProbeConfig
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlinx.coroutines.withTimeoutOrNull
import org.w3c.dom.events.Event
import org.w3c.fetch.CORS
import org.w3c.fetch.NO_CACHE
import org.w3c.fetch.NO_CORS
import org.w3c.fetch.RequestCache
import org.w3c.fetch.RequestInit
import org.w3c.fetch.RequestMode
import org.w3c.fetch.SAME_ORIGIN

/** The browser boundary of the web source; faked in tests. */
internal interface BrowserApi {
    val isOnline: Boolean
    val isHidden: Boolean

    /** Calls [listener] with `true` on `online` and `false` on `offline`. */
    fun onOnlineChange(listener: (Boolean) -> Unit): AutoCloseable

    /** Calls [listener] with `document.hidden` on `visibilitychange`. */
    fun onVisibilityChange(listener: (Boolean) -> Unit): AutoCloseable

    /** `true` if any response arrived within the timeout. */
    suspend fun probe(config: WebProbeConfig): Boolean
}

internal expect fun isDocumentHidden(): Boolean

internal class SystemBrowserApi : BrowserApi {

    override val isOnline: Boolean get() = window.navigator.onLine

    override val isHidden: Boolean get() = isDocumentHidden()

    override fun onOnlineChange(listener: (Boolean) -> Unit): AutoCloseable {
        val online: (Event) -> Unit = { listener(true) }
        val offline: (Event) -> Unit = { listener(false) }
        window.addEventListener("online", online)
        window.addEventListener("offline", offline)
        return AutoCloseable {
            window.removeEventListener("online", online)
            window.removeEventListener("offline", offline)
        }
    }

    override fun onVisibilityChange(listener: (Boolean) -> Unit): AutoCloseable {
        val changed: (Event) -> Unit = { listener(isDocumentHidden()) }
        document.addEventListener("visibilitychange", changed)
        return AutoCloseable { document.removeEventListener("visibilitychange", changed) }
    }

    override suspend fun probe(config: WebProbeConfig): Boolean = try {
        val init = RequestInit(
            method = config.method,
            mode = when (config.mode) {
                FetchMode.SameOrigin -> RequestMode.SAME_ORIGIN
                FetchMode.Cors -> RequestMode.CORS
                FetchMode.NoCors -> RequestMode.NO_CORS
            },
            cache = RequestCache.NO_CACHE,
        )
        // Any response (even an error status or an opaque no-cors one) proves reachability.
        withTimeoutOrNull(config.timeout) { window.fetch(checkNotNull(config.url), init).await() } != null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        false // network TypeError: unreachable
    }
}
