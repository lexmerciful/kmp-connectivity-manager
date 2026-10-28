import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.lexmerciful.connectivity.ConnectivityLogger
import io.github.lexmerciful.connectivity.ConnectivityMonitor
import io.github.lexmerciful.connectivity.config.ProbeTargets
import io.github.lexmerciful.connectivity.config.Schedule
import io.github.lexmerciful.connectivity.ktor.ConnectivityHints
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import sample.app.App
import java.awt.Dimension
import kotlin.time.Duration.Companion.seconds

private const val API_BASE_URL = "https://example.com"

fun main() {
    // One monitor per process. The probe targets are explicit: nothing is contacted implicitly.
    val monitor = ConnectivityMonitor {
        logger = ConnectivityLogger.Println
        desktop {
            tcp {
                targets = ProbeTargets.PublicResolvers + ProbeTargets.fromUrl(API_BASE_URL)
                connectTimeout = 2.seconds
            }
            checkBound = 7.seconds
            schedule = Schedule.Adaptive(online = 30.seconds, offline = 3.seconds)
        }
    }
    val client = HttpClient(CIO) {
        install(ConnectivityHints) { this.monitor = monitor }
    }

    application {
        Window(
            title = "KMP Connectivity sample",
            state = rememberWindowState(width = 800.dp, height = 700.dp),
            onCloseRequest = {
                client.close()
                monitor.close()
                exitApplication()
            },
        ) {
            window.minimumSize = Dimension(350, 600)
            App(monitor, ping = { client.get(API_BASE_URL).status.toString() })
        }
    }
}
