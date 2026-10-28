package sample.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.lexmerciful.connectivity.ConnectivityMonitor
import io.github.lexmerciful.connectivity.compose.BackOnlineConfig
import io.github.lexmerciful.connectivity.compose.ConnectivityStrip
import io.github.lexmerciful.connectivity.compose.ConnectivityStripDefaults
import io.github.lexmerciful.connectivity.compose.ConnectivityStripLayout
import io.github.lexmerciful.connectivity.compose.ConnectivityStripState
import sample.app.connectivity.ConnectivityRoot
import sample.app.connectivity.ConnectivityViewModel
import sample.app.connectivity.StripStyle
import kotlin.time.Duration.Companion.seconds

/**
 * The sample app. Each platform creates **one** [monitor] at its entry point and passes it here.
 *
 * @param ping optional request through an HTTP client that has the connectivity hints installed.
 */
@Composable
fun App(monitor: ConnectivityMonitor, ping: (suspend () -> String)? = null) {
    MaterialTheme {
        val viewModel = viewModel { ConnectivityViewModel(monitor, ping) }
        val state by viewModel.state.collectAsStateWithLifecycle()

        // Added once around the root: no screen changes needed.
        ConnectivityStripLayout(
            monitor = monitor,
            position = state.position,
            mode = state.mode,
            backOnline = if (state.backOnlineEnabled) BackOnlineConfig(duration = 2.seconds) else null,
            strip = { stripState ->
                when (state.style) {
                    StripStyle.Default -> ConnectivityStrip(stripState)
                    StripStyle.Customised -> ConnectivityStrip(
                        state = stripState,
                        offlineText = "You're offline. Changes will sync later.",
                        backOnlineText = "You're back online",
                        colors = ConnectivityStripDefaults.colors(
                            offlineContainerColor = Color(0xFF37474F),
                            offlineContentColor = Color(0xFFFFE082),
                        ),
                        textStyle = MaterialTheme.typography.labelLarge,
                        icon = { Text(if (it == ConnectivityStripState.Offline) "⚠" else "✓") },
                        action = { TextButton(onClick = monitor::requestRecheck) { Text("Retry") } },
                    )
                    StripStyle.CustomComposable -> CustomBanner(stripState)
                }
            },
        ) {
            ConnectivityRoot(viewModel)
        }
    }
}

/** Any composable can replace the strip. */
@Composable
private fun CustomBanner(state: ConnectivityStripState) {
    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
        val (text, color) = when (state) {
            ConnectivityStripState.Offline -> "Offline mode" to Color(0xFFB00020)
            ConnectivityStripState.BackOnline -> "Connected" to Color(0xFF00695C)
        }
        androidx.compose.material3.Surface(color = color, contentColor = Color.White, shape = RoundedCornerShape(50)) {
            Text(text, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
        }
    }
}
