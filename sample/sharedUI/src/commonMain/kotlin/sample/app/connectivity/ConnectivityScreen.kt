package sample.app.connectivity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lexmerciful.connectivity.compose.StripMode
import io.github.lexmerciful.connectivity.compose.StripPosition

/** Root: wires the ViewModel (state + events) to the stateless [ConnectivityScreen]. */
@Composable
fun ConnectivityRoot(viewModel: ConnectivityViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ConnectivityEvent.ShowMessage -> snackbarHostState.showSnackbar(event.text)
            }
        }
    }
    ConnectivityScreen(state = state, snackbarHostState = snackbarHostState, onAction = viewModel::onAction)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectivityScreen(
    state: ConnectivityState,
    snackbarHostState: SnackbarHostState,
    onAction: (ConnectivityAction) -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("KMP Connectivity") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(if (state.isConnected) "Online" else "Offline", style = MaterialTheme.typography.headlineMedium)
            Text(state.statusText)
            Text("Reconnections this session: ${state.reconnections}")

            Section("Strip") {
                ChoiceRow(StripStyle.entries, state.style) { onAction(ConnectivityAction.SelectStyle(it)) }
                ChoiceRow(StripPosition.entries, state.position) { onAction(ConnectivityAction.SelectPosition(it)) }
                ChoiceRow(StripMode.entries, state.mode) { onAction(ConnectivityAction.SelectMode(it)) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(checked = state.backOnlineEnabled, onCheckedChange = { onAction(ConnectivityAction.SetBackOnline(it)) })
                    Text("\"Back online\" confirmation")
                }
            }

            Section("Per-screen behaviour") {
                Button(onClick = { onAction(ConnectivityAction.Pay) }, enabled = state.isConnected, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.isConnected) "Pay" else "Pay (needs a connection)")
                }
                if (state.canPing) {
                    OutlinedButton(
                        onClick = { onAction(ConnectivityAction.Ping) },
                        enabled = !state.isPinging,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (state.isPinging) "Pinging..." else "Ping API (feeds request hints)") }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun <T> ChoiceRow(options: List<T>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(option.toString()) })
        }
    }
}
