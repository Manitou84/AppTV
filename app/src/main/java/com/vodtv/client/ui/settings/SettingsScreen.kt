package com.vodtv.client.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.vodtv.client.data.tmdb.TmdbRepository
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
    val sourceDomain: String = "",
    val iframeDomain: String = "",
    val tmdbApiKey: String = "",
    val feedback: String? = null,
    val isError: Boolean = false,
    val busy: Boolean = false,
    val confirmClear: Boolean = false,
)

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val vod: VodRepository,
    private val tmdb: TmdbRepository,
    private val library: LibraryRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val s = settings.current()
            _state.update {
                it.copy(baseUrl = s.baseUrl, sourceDomain = s.sourceDomain, iframeDomain = s.iframeDomain, tmdbApiKey = s.tmdbApiKey)
            }
        }
    }

    fun onBaseUrlChange(value: String) = _state.update { it.copy(baseUrl = value, feedback = null) }
    fun onSourceDomainChange(value: String) = _state.update { it.copy(sourceDomain = value, feedback = null) }
    fun onIframeChange(value: String) = _state.update { it.copy(iframeDomain = value, feedback = null) }
    fun onTmdbKeyChange(value: String) = _state.update { it.copy(tmdbApiKey = value, feedback = null) }

    fun save() {
        if (_state.value.baseUrl.isBlank()) {
            _state.update { it.copy(feedback = "Il Base URL è obbligatorio.", isError = true) }
            return
        }
        viewModelScope.launch {
            persist()
            _state.update { it.copy(feedback = "Impostazioni salvate.", isError = false) }
        }
    }

    /** Salva, poi prova una ricerca reale sul server e (se impostata) la chiave TMDB. */
    fun verify() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, feedback = "Verifico…", isError = false) }
            persist()
            val server = vod.search("a").fold(
                onSuccess = { "Server: ok (${it.size} risultati di prova)." to false },
                onFailure = { "Server: ${it.toUserMessage()}" to true },
            )
            val key = _state.value.tmdbApiKey
            val tmdbResult = if (key.isBlank()) {
                "TMDB: chiave non impostata, uso i dati del server." to false
            } else {
                tmdb.checkKey(key).fold(
                    onSuccess = { "TMDB: chiave valida." to false },
                    onFailure = { "TMDB: chiave non valida o servizio non raggiungibile." to true },
                )
            }
            _state.update {
                it.copy(busy = false, feedback = "${server.first}\n${tmdbResult.first}", isError = server.second || tmdbResult.second)
            }
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

    private suspend fun persist() {
        val s = _state.value
        settings.update(s.baseUrl, s.iframeDomain, s.sourceDomain, s.tmdbApiKey)
        val saved = settings.current()
        _state.update {
            it.copy(baseUrl = saved.baseUrl, sourceDomain = saved.sourceDomain, iframeDomain = saved.iframeDomain, tmdbApiKey = saved.tmdbApiKey)
        }
    }

    companion object {
        val Factory = appViewModelFactory { c, _ ->
            SettingsViewModel(c.settingsRepository, c.vodRepository, c.tmdbRepository, c.libraryRepository)
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
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        TopNavBar(current = NavTab.SETTINGS, onSelect = onSelectTab)

        // Scorre da sola quando il focus scende sui campi più in basso
        Column(
            modifier = Modifier
                .width(760.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Impostazioni", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = VodColors.Text)

            SettingField(
                value = state.baseUrl,
                onValueChange = viewModel::onBaseUrlChange,
                label = "Base URL del server",
                placeholder = "http://server-vod.esempio",
                hint = "Il tuo backend: /api/search, /api/load e /api/get_links",
            )
            SettingField(
                value = state.sourceDomain,
                onValueChange = viewModel::onSourceDomainChange,
                label = "Dominio sorgente di film e serie",
                placeholder = "sito-sorgente.esempio",
                hint = "Sito da cui il backend recupera i titoli; inviato come parametro \"domain\". Vuoto = predefinito del server",
            )
            SettingField(
                value = state.iframeDomain,
                onValueChange = viewModel::onIframeChange,
                label = "Dominio iframe",
                placeholder = "dominio-iframe.esempio",
                hint = "Passato come iframe_domain a get_links e inviato come Referer al player",
            )
            SettingField(
                value = state.tmdbApiKey,
                onValueChange = viewModel::onTmdbKeyChange,
                label = "Chiave API TMDB",
                placeholder = "chiave v3 oppure Read Access Token v4",
                hint = "Titoli, trame, locandine ed episodi in italiano da themoviedb.org (gratuita)",
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
                onImeAction = viewModel::save,
            )

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
            Text("OK apre la tastiera · ▼ passa al campo successivo", fontSize = 14.sp, color = VodColors.TextMuted)
        }
    }
}

@Composable
private fun SettingField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    hint: String,
    keyboardType: KeyboardType = KeyboardType.Uri,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TvTextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            placeholder = placeholder,
            keyboardType = keyboardType,
            imeAction = imeAction,
            onImeAction = onImeAction,
            modifier = Modifier.width(720.dp),
        )
        Text(hint, fontSize = 14.sp, color = VodColors.TextMuted)
    }
}
