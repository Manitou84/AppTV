package com.vodtv.client.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import com.vodtv.client.data.repository.LibraryRepository
import com.vodtv.client.data.repository.VodRepository
import com.vodtv.client.data.settings.SettingsRepository
import com.vodtv.client.ui.common.appViewModelFactory
import com.vodtv.client.ui.common.toUserMessage
import com.vodtv.client.ui.components.ButtonTextPadding
import com.vodtv.client.ui.components.NavTab
import com.vodtv.client.ui.components.PrimaryButton
import com.vodtv.client.ui.components.SecondaryButton
import com.vodtv.client.ui.components.TopNavBar
import com.vodtv.client.ui.components.TvTextField
import com.vodtv.client.ui.theme.VodColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val baseUrl: String = "",
    val iframeDomain: String = "",
    val feedback: String? = null,
    val isError: Boolean = false,
    val busy: Boolean = false,
    val confirmClear: Boolean = false,
)

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val vod: VodRepository,
    private val library: LibraryRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val s = settings.current()
            _state.update { it.copy(baseUrl = s.baseUrl, iframeDomain = s.iframeDomain) }
        }
    }

    fun onBaseUrlChange(value: String) = _state.update { it.copy(baseUrl = value, feedback = null) }

    fun onIframeChange(value: String) = _state.update { it.copy(iframeDomain = value, feedback = null) }

    fun save() {
        val s = _state.value
        if (s.baseUrl.isBlank()) {
            _state.update { it.copy(feedback = "Il Base URL è obbligatorio.", isError = true) }
            return
        }
        viewModelScope.launch {
            settings.update(s.baseUrl, s.iframeDomain)
            val saved = settings.current()
            _state.update {
                it.copy(baseUrl = saved.baseUrl, iframeDomain = saved.iframeDomain, feedback = "Impostazioni salvate.", isError = false)
            }
        }
    }

    /** Salva e prova una ricerca reale sul server. */
    fun verify() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, feedback = "Verifico…", isError = false) }
            settings.update(_state.value.baseUrl, _state.value.iframeDomain)
            vod.search("a")
                .onSuccess { r -> _state.update { it.copy(busy = false, feedback = "Server raggiungibile: ${r.size} risultati di prova.", isError = false) } }
                .onFailure { e -> _state.update { it.copy(busy = false, feedback = e.toUserMessage(), isError = true) } }
        }
    }

    /** Doppia pressione per confermare: l'operazione non si può annullare. */
    fun clearHistory() {
        if (!_state.value.confirmClear) {
            _state.update { it.copy(confirmClear = true, feedback = "Premi di nuovo per cancellare tutta la cronologia di visione.", isError = true) }
            return
        }
        viewModelScope.launch {
            library.clearHistory()
            _state.update { it.copy(confirmClear = false, feedback = "Cronologia di visione cancellata.", isError = false) }
        }
    }

    companion object {
        val Factory = appViewModelFactory { c, _ ->
            SettingsViewModel(c.settingsRepository, c.vodRepository, c.libraryRepository)
        }
    }
}

@Composable
fun SettingsScreen(
    onSelectTab: (NavTab) -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 64.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        TopNavBar(current = NavTab.SETTINGS, onSelect = onSelectTab)
        Text("Impostazioni", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = VodColors.Text)

        Column(Modifier.width(720.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TvTextField(
                    value = state.baseUrl,
                    onValueChange = viewModel::onBaseUrlChange,
                    label = "Base URL del server",
                    placeholder = "http://server-vod.esempio",
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                    modifier = Modifier.width(720.dp),
                )
                Text("Usato per /api/search, /api/load e /api/get_links", fontSize = 14.sp, color = VodColors.TextMuted)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TvTextField(
                    value = state.iframeDomain,
                    onValueChange = viewModel::onIframeChange,
                    label = "Dominio iframe",
                    placeholder = "dominio-iframe.esempio",
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                    onImeAction = viewModel::save,
                    modifier = Modifier.width(720.dp),
                )
                Text("Passato come iframe_domain e inviato come Referer al player", fontSize = 14.sp, color = VodColors.TextMuted)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PrimaryButton(onClick = viewModel::save) { Text("Salva", fontSize = 18.sp, modifier = ButtonTextPadding) }
                SecondaryButton(onClick = viewModel::verify) {
                    Text(if (state.busy) "Verifico…" else "Verifica connessione", fontSize = 18.sp, modifier = ButtonTextPadding)
                }
                SecondaryButton(onClick = viewModel::clearHistory) {
                    Text(if (state.confirmClear) "Conferma cancellazione" else "Cancella cronologia", fontSize = 18.sp, modifier = ButtonTextPadding)
                }
            }
            state.feedback?.let {
                Text(it, fontSize = 16.sp, color = if (state.isError) VodColors.Accent else VodColors.Watched)
            }
        }
        Text("OK apre la tastiera · ▼ passa al campo successivo", fontSize = 14.sp, color = VodColors.TextMuted)
    }
}
