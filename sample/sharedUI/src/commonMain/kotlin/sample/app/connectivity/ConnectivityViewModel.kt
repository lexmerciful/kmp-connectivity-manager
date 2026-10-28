package sample.app.connectivity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lexmerciful.connectivity.ConnectivityMonitor
import io.github.lexmerciful.connectivity.ConnectivityStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * App-level presenter: one per app, collected at the root. Screens read [state] for per-screen
 * behaviour (e.g. disabling "Pay" while offline) instead of adding their own banners.
 */
class ConnectivityViewModel(
    private val monitor: ConnectivityMonitor,
    private val ping: (suspend () -> String)?,
) : ViewModel() {

    private val uiOptions = MutableStateFlow(ConnectivityState(canPing = ping != null))
    private val reconnections = MutableStateFlow(0)

    val state: StateFlow<ConnectivityState> =
        combine(uiOptions, monitor.status, reconnections) { options, status, count ->
            options.copy(
                isConnected = status !is ConnectivityStatus.Disconnected,
                statusText = status.describe(),
                reconnections = count,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), uiOptions.value)

    private val eventChannel = Channel<ConnectivityEvent>(Channel.BUFFERED)
    val events: Flow<ConnectivityEvent> = eventChannel.receiveAsFlow()

    init {
        // Auto-retry hook: the library only signals; the app decides what to reload.
        viewModelScope.launch {
            monitor.reconnected.collect {
                reconnections.update { it + 1 }
                eventChannel.send(ConnectivityEvent.ShowMessage("Reconnected: reloading data"))
            }
        }
    }

    fun onAction(action: ConnectivityAction) {
        when (action) {
            is ConnectivityAction.SelectStyle -> uiOptions.update { it.copy(style = action.style) }
            is ConnectivityAction.SelectPosition -> uiOptions.update { it.copy(position = action.position) }
            is ConnectivityAction.SelectMode -> uiOptions.update { it.copy(mode = action.mode) }
            is ConnectivityAction.SetBackOnline -> uiOptions.update { it.copy(backOnlineEnabled = action.enabled) }
            ConnectivityAction.Pay -> viewModelScope.launch { eventChannel.send(ConnectivityEvent.ShowMessage("Payment sent")) }
            ConnectivityAction.Ping -> ping()
        }
    }

    private fun ping() {
        val request = ping ?: return
        uiOptions.update { it.copy(isPinging = true) }
        viewModelScope.launch {
            val message = runCatching { request() }.fold({ "Ping: $it" }, { "Ping failed: ${it.message ?: it::class.simpleName}" })
            uiOptions.update { it.copy(isPinging = false) }
            eventChannel.send(ConnectivityEvent.ShowMessage(message))
        }
    }

    private fun ConnectivityStatus.describe(): String = when (this) {
        ConnectivityStatus.Unknown -> "Unknown"
        is ConnectivityStatus.Connected -> buildString {
            append("Connected via ").append(transport)
            isMetered?.let { append(if (it) ", metered" else ", unmetered") }
            if (isConstrained == true) append(", constrained")
        }
        is ConnectivityStatus.Disconnected -> "Disconnected ($reason)"
        is ConnectivityStatus.MonitorError -> "Monitor error: ${cause.message}"
    }
}
