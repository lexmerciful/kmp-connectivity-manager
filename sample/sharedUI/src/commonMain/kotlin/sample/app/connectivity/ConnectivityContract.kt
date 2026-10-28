package sample.app.connectivity

import io.github.lexmerciful.connectivity.compose.StripMode
import io.github.lexmerciful.connectivity.compose.StripPosition

/** Which strip the demo renders. */
enum class StripStyle { Default, Customised, CustomComposable }

data class ConnectivityState(
    val isConnected: Boolean = true,
    val statusText: String = "",
    val reconnections: Int = 0,
    val style: StripStyle = StripStyle.Default,
    val position: StripPosition = StripPosition.Top,
    val mode: StripMode = StripMode.PushContent,
    val backOnlineEnabled: Boolean = false,
    val isPinging: Boolean = false,
    val canPing: Boolean = false,
)

sealed interface ConnectivityAction {
    data class SelectStyle(val style: StripStyle) : ConnectivityAction
    data class SelectPosition(val position: StripPosition) : ConnectivityAction
    data class SelectMode(val mode: StripMode) : ConnectivityAction
    data class SetBackOnline(val enabled: Boolean) : ConnectivityAction
    data object Pay : ConnectivityAction
    data object Ping : ConnectivityAction
}

sealed interface ConnectivityEvent {
    data class ShowMessage(val text: String) : ConnectivityEvent
}
