import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import io.github.lexmerciful.connectivity.ConnectivityMonitor
import sample.app.App

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // navigator.onLine + online/offline events; add web { probe { enabled = true; url = "/health" } } for a fetch probe.
    val monitor = ConnectivityMonitor()
    ComposeViewport { App(monitor) }
}
