import androidx.compose.ui.window.ComposeUIViewController
import io.github.lexmerciful.connectivity.ConnectivityMonitor
import platform.UIKit.UIViewController
import sample.app.App

/** One monitor per process. */
private val monitor by lazy { ConnectivityMonitor() }

fun MainViewController(): UIViewController = ComposeUIViewController { App(monitor) }
