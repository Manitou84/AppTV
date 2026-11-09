package com.vodtv.client.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.tv.material3.Border
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.Text
import com.vodtv.client.data.profile.Profile
import com.vodtv.client.data.profile.ProfileRepository
import com.vodtv.client.data.remote.UrlUtils
import com.vodtv.client.data.repository.LibraryRepository
import com.vodtv.client.data.repository.VodRepository
import com.vodtv.client.data.settings.AppSettings
import com.vodtv.client.data.tmdb.TmdbRepository
import com.vodtv.client.ui.common.appViewModelFactory
import com.vodtv.client.ui.common.toUserMessage
import com.vodtv.client.ui.components.ButtonTextPadding
import com.vodtv.client.ui.components.NavTab
import com.vodtv.client.ui.components.PrimaryButton
import com.vodtv.client.ui.components.SecondaryButton
import com.vodtv.client.ui.components.TopNavBar
import com.vodtv.client.ui.components.TvTextField
import com.vodtv.client.ui.profile.PinPadDialog
import com.vodtv.client.ui.profile.ProfileAvatar
import com.vodtv.client.ui.theme.VodColors
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileForm(
    val name: String = "",
    val baseUrl: String = "",
    val sourceDomain: String = "",
    val iframeDomain: String = "",
    val tmdbApiKey: String = "",
) {
    fun toSettings() = AppSettings(
        baseUrl = UrlUtils.normalizeBaseUrl(baseUrl),
        iframeDomain = UrlUtils.normalizeDomain(iframeDomain),
        sourceDomain = UrlUtils.normalizeDomain(sourceDomain),
        tmdbApiKey = tmdbApiKey.trim(),
    )
}

enum class PinStep { ENTER, CONFIRM }

data class SettingsUiState(
    val profiles: List<Profile> = emptyList(),
    val activeId: String? = null,
    val editingId: String? = null,
    val form: ProfileForm = ProfileForm(),
    val hasPin: Boolean = false,
    val feedback: String? = null,
    val isError: Boolean = false,
    val busy: Boolean = false,
    val confirmDelete: Boolean = false,
    val confirmClear: Boolean = false,
    val pinStep: PinStep? = null,
    val pinError: String? = null,
) {
    val editing: Profile? get() = profiles.firstOrNull { it.id == editingId }
}

/**
 * Impostazioni multi-profilo (accessibili solo dai profili standard):
 * elenco profili, modifica del profilo selezionato (ognuno con server, domini e chiave TMDB propri),
 * creazione profili standard e bambini, PIN genitori.
 */
class SettingsViewModel(
    private val profiles: ProfileRepository,
    private val vod: VodRepository,
    private val tmdb: TmdbRepository,
    private val library: LibraryRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val _switched = Channel<Unit>(Channel.BUFFERED)
    val switched: Flow<Unit> = _switched.receiveAsFlow()

    private var firstPin: String? = null
    private var createKidAfterPin = false

    init {
        viewModelScope.launch {
            combine(profiles.profiles.filterNotNull(), profiles.active, profiles.hasPin) { list, active, pin ->
                Triple(list, active, pin)
            }.collect { (list, active, pin) ->
                _state.update { it.copy(profiles = list, activeId = active?.id, hasPin = pin) }
                val editing = _state.value.editingId
                if (editing == null || list.none { it.id == editing }) {
                    (active ?: list.firstOrNull())?.let { loadForm(it) }
                }
            }
        }
    }

    fun selectProfile(id: String) {
        if (id == _state.value.editingId) return
        _state.value.profiles.firstOrNull { it.id == id }?.let { loadForm(it) }
    }

    private fun loadForm(p: Profile) {
        _state.update {
            it.copy(
                editingId = p.id,
                form = ProfileForm(p.name, p.baseUrl, p.sourceDomain, p.iframeDomain, p.tmdbApiKey),
                feedback = null,
                confirmDelete = false,
                confirmClear = false,
            )
        }
    }

    fun onFormChange(form: ProfileForm) = _state.update { it.copy(form = form, feedback = null) }

    fun save() {
        val p = _state.value.editing ?: return
        if (_state.value.form.name.isBlank()) {
            _state.update { it.copy(feedback = "Il nome del profilo è obbligatorio.", isError = true) }
            return
        }
        viewModelScope.launch {
            persist(p)
            _state.update { it.copy(feedback = "Profilo «${it.form.name.trim()}» salvato.", isError = false) }
        }
    }

    /** Salva, poi prova il server e la chiave TMDB di QUESTO profilo (anche se non è quello attivo). */
    fun verify() {
        val p = _state.value.editing ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, feedback = "Verifico…", isError = false) }
            persist(p)
            val settings = _state.value.form.toSettings()
            val server = if (!settings.isConfigured) {
                "Server: Base URL non impostato." to true
            } else {
                vod.search("a", override = settings).fold(
                    onSuccess = { "Server: ok (${it.size} risultati di prova)." to false },
                    onFailure = { "Server: ${it.toUserMessage()}" to true },
                )
            }
            val tmdbResult = if (!settings.hasTmdb) {
                "TMDB: chiave non impostata (i profili bambini ne hanno bisogno)." to p.isKid
            } else {
                tmdb.checkKey(settings.tmdbApiKey).fold(
                    onSuccess = { "TMDB: chiave valida." to false },
                    onFailure = { "TMDB: chiave non valida o servizio non raggiungibile." to true },
                )
            }
            _state.update {
                it.copy(busy = false, feedback = "${server.first}\n${tmdbResult.first}", isError = server.second || tmdbResult.second)
            }
        }
    }

    /** Nuovo profilo con le impostazioni di server del profilo selezionato. Bambini: serve prima il PIN. */
    fun createProfile(kid: Boolean) {
        if (kid && !_state.value.hasPin) {
            createKidAfterPin = true
            startPinSetup(message = "Per creare un profilo bambini imposta prima il PIN genitori.")
            return
        }
        viewModelScope.launch {
            val template = _state.value.editing ?: profiles.active.value
            val created = profiles.create(name = "", isKid = kid, template = template)
            // Attende che il nuovo profilo arrivi dal database, poi lo apre in modifica
            profiles.profiles.filterNotNull().first { list -> list.any { it.id == created.id } }
            loadForm(created)
            _state.update {
                it.copy(
                    feedback = if (kid) "Profilo bambini creato: vede solo titoli per bambini verificati con TMDB."
                    else "Nuovo profilo creato: cambia nome e impostazioni, poi Salva.",
                    isError = false,
                )
            }
        }
    }

    fun useProfile() {
        val p = _state.value.editing ?: return
        viewModelScope.launch {
            persist(p)
            profiles.switchTo(p.id)
            _switched.send(Unit)
        }
    }

    fun delete() {
        val s = _state.value
        val p = s.editing ?: return
        when {
            p.id == s.activeId -> {
                _state.update { it.copy(feedback = "Non puoi eliminare il profilo in uso: passa prima a un altro profilo.", isError = true) }
                return
            }
            !p.isKid && s.profiles.count { !it.isKid } <= 1 -> {
                _state.update { it.copy(feedback = "Serve almeno un profilo standard.", isError = true) }
                return
            }
            !s.confirmDelete -> {
                _state.update { it.copy(confirmDelete = true, feedback = "Premi di nuovo per eliminare «${p.name}» con preferiti e cronologia.", isError = true) }
                return
            }
        }
        viewModelScope.launch {
            profiles.delete(p.id)
            _state.update { it.copy(editingId = null, confirmDelete = false, feedback = "Profilo eliminato.", isError = false) }
            (profiles.active.value ?: _state.value.profiles.firstOrNull())?.let { loadForm(it) }
        }
    }

    fun clearHistory() {
        val p = _state.value.editing ?: return
        if (!_state.value.confirmClear) {
            _state.update { it.copy(confirmClear = true, feedback = "Premi di nuovo per cancellare la cronologia di «${p.name}».", isError = true) }
            return
        }
        viewModelScope.launch {
            library.clearHistory(p.id)
            _state.update { it.copy(confirmClear = false, feedback = "Cronologia di «${p.name}» cancellata.", isError = false) }
        }
    }

    // ---------- PIN genitori ----------

    fun startPinSetup(message: String? = null) {
        firstPin = null
        _state.update { it.copy(pinStep = PinStep.ENTER, pinError = null, feedback = message, isError = message != null) }
    }

    fun onPinEntered(pin: String) {
        when (_state.value.pinStep) {
            PinStep.ENTER -> {
                firstPin = pin
                _state.update { it.copy(pinStep = PinStep.CONFIRM, pinError = null) }
            }
            PinStep.CONFIRM -> {
                if (pin != firstPin) {
                    firstPin = null
                    _state.update { it.copy(pinStep = PinStep.ENTER, pinError = "I due PIN non coincidono, riprova.") }
                    return
                }
                viewModelScope.launch {
                    profiles.setPin(pin)
                    firstPin = null
                    _state.update { it.copy(pinStep = null, pinError = null, feedback = "PIN genitori salvato.", isError = false) }
                    if (createKidAfterPin) {
                        createKidAfterPin = false
                        createProfile(kid = true)
                    }
                }
            }
            null -> Unit
        }
    }

    fun cancelPin() {
        firstPin = null
        createKidAfterPin = false
        _state.update { it.copy(pinStep = null, pinError = null) }
    }

    private suspend fun persist(p: Profile) {
        val f = _state.value.form
        profiles.update(
            p.copy(name = f.name, baseUrl = f.baseUrl, sourceDomain = f.sourceDomain, iframeDomain = f.iframeDomain, tmdbApiKey = f.tmdbApiKey),
        )
        // Riallinea il modulo ai valori normalizzati (es. "http://" aggiunto al Base URL)
        val n = f.toSettings()
        _state.update { it.copy(form = f.copy(baseUrl = n.baseUrl, sourceDomain = n.sourceDomain, iframeDomain = n.iframeDomain, tmdbApiKey = n.tmdbApiKey)) }
    }

    companion object {
        val Factory = appViewModelFactory { c, _ ->
            SettingsViewModel(c.profileRepository, c.vodRepository, c.tmdbRepository, c.libraryRepository)
        }
    }
}

@Composable
fun SettingsScreen(
    onSelectTab: (NavTab) -> Unit,
    onProfileSwitched: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) { viewModel.switched.collect { onProfileSwitched() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 64.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        TopNavBar(current = NavTab.SETTINGS, onSelect = onSelectTab)

        Row(horizontalArrangement = Arrangement.spacedBy(40.dp), modifier = Modifier.fillMaxSize()) {
            ProfileList(state, viewModel, Modifier.width(320.dp).fillMaxHeight())
            ProfileEditor(state, viewModel, Modifier.weight(1f).fillMaxHeight())
        }
    }

    state.pinStep?.let { step ->
        PinPadDialog(
            title = if (step == PinStep.ENTER) "Nuovo PIN genitori" else "Conferma il PIN",
            subtitle = if (step == PinStep.ENTER) "4 cifre: servirà per uscire dai profili bambini" else "Inserisci di nuovo le stesse 4 cifre",
            error = state.pinError,
            onComplete = viewModel::onPinEntered,
            onDismiss = viewModel::cancelPin,
        )
    }
}

@Composable
private fun ProfileList(state: SettingsUiState, viewModel: SettingsViewModel, modifier: Modifier) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Profili", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = VodColors.Text)
        state.profiles.forEach { p ->
            ListItem(
                selected = p.id == state.editingId,
                onClick = { viewModel.selectProfile(p.id) },
                shape = ListItemDefaults.shape(shape = RoundedCornerShape(12.dp)),
                colors = ListItemDefaults.colors(
                    containerColor = VodColors.Surface,
                    contentColor = VodColors.Text,
                    selectedContainerColor = VodColors.SurfaceHigh,
                    selectedContentColor = VodColors.Text,
                    focusedContainerColor = VodColors.SurfaceHigh,
                    focusedContentColor = VodColors.Text,
                ),
                border = ListItemDefaults.border(
                    focusedBorder = Border(BorderStroke(3.dp, VodColors.Accent), shape = RoundedCornerShape(12.dp)),
                ),
                leadingContent = { ProfileAvatar(p, size = 40.dp) },
                headlineContent = { Text(p.name, fontSize = 18.sp, maxLines = 1) },
                supportingContent = {
                    val kind = if (p.isKid) "Bambini" else "Standard"
                    Text(if (p.id == state.activeId) "$kind · in uso" else kind, fontSize = 14.sp, color = VodColors.TextMuted)
                },
            )
        }
        SecondaryButton(onClick = { viewModel.createProfile(kid = false) }, modifier = Modifier.fillMaxWidth()) {
            Text("+ Nuovo profilo", fontSize = 17.sp, modifier = ButtonTextPadding)
        }
        SecondaryButton(onClick = { viewModel.createProfile(kid = true) }, modifier = Modifier.fillMaxWidth()) {
            Text("+ Nuovo profilo bambini", fontSize = 17.sp, modifier = ButtonTextPadding)
        }
        SecondaryButton(onClick = { viewModel.startPinSetup() }, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.hasPin) "Cambia PIN genitori" else "Imposta PIN genitori", fontSize = 17.sp, modifier = ButtonTextPadding)
        }
    }
}

@Composable
private fun ProfileEditor(state: SettingsUiState, viewModel: SettingsViewModel, modifier: Modifier) {
    val p = state.editing ?: return
    val f = state.form
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            if (p.isKid) "Profilo bambini" else "Profilo standard",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = VodColors.TextMuted,
            letterSpacing = 1.2.sp,
        )
        SettingField(f.name, { viewModel.onFormChange(f.copy(name = it)) }, "Nome del profilo", "Es. Alberto", "Come appare in «Chi guarda?»", KeyboardType.Text)
        SettingField(f.baseUrl, { viewModel.onFormChange(f.copy(baseUrl = it)) }, "Base URL del server", "http://server-vod.esempio", "Il tuo backend: /api/search, /api/load e /api/get_links")
        SettingField(
            f.sourceDomain, { viewModel.onFormChange(f.copy(sourceDomain = it)) },
            "Dominio sorgente di film e serie", "sito-sorgente.esempio",
            "Scrivilo a mano: sito da cui il backend recupera i titoli (parametro \"domain\"). Vuoto = predefinito del server",
        )
        SettingField(
            f.iframeDomain, { viewModel.onFormChange(f.copy(iframeDomain = it)) },
            "Dominio iframe", "dominio-iframe.esempio",
            "Scrivilo a mano: passato come iframe_domain a get_links e come Referer al player",
        )
        SettingField(
            f.tmdbApiKey, { viewModel.onFormChange(f.copy(tmdbApiKey = it)) },
            "Chiave API TMDB", "chiave v3 oppure Read Access Token v4",
            if (p.isKid) "Obbligatoria per i bambini: serve a riconoscere i titoli adatti"
            else "Titoli, trame, locandine ed episodi in italiano da themoviedb.org",
            KeyboardType.Ascii, ImeAction.Done, viewModel::save,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PrimaryButton(onClick = viewModel::save) { Text("Salva", fontSize = 17.sp, modifier = ButtonTextPadding) }
            SecondaryButton(onClick = viewModel::verify) {
                Text(if (state.busy) "Verifico…" else "Verifica", fontSize = 17.sp, modifier = ButtonTextPadding)
            }
            if (p.id != state.activeId) {
                SecondaryButton(onClick = viewModel::useProfile) { Text("Usa questo profilo", fontSize = 17.sp, modifier = ButtonTextPadding) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SecondaryButton(onClick = viewModel::clearHistory) {
                Text(if (state.confirmClear) "Conferma: cancella cronologia" else "Cancella cronologia", fontSize = 17.sp, modifier = ButtonTextPadding)
            }
            if (p.id != state.activeId) {
                SecondaryButton(onClick = viewModel::delete) {
                    Text(if (state.confirmDelete) "Conferma eliminazione" else "Elimina profilo", fontSize = 17.sp, modifier = ButtonTextPadding)
                }
            }
        }
        state.feedback?.let {
            Text(it, fontSize = 16.sp, color = if (state.isError) VodColors.Accent else VodColors.Watched)
        }
        Text("OK apre la tastiera · ▼ passa al campo successivo", fontSize = 14.sp, color = VodColors.TextMuted)
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
            modifier = Modifier.width(680.dp),
        )
        Text(hint, fontSize = 14.sp, color = VodColors.TextMuted)
    }
}
