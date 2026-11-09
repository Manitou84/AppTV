package com.vodtv.client.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vodtv.client.data.model.ContentType
import com.vodtv.client.data.model.SearchResult
import com.vodtv.client.data.repository.VodRepository
import com.vodtv.client.data.settings.SettingsRepository
import com.vodtv.client.ui.common.appViewModelFactory
import com.vodtv.client.ui.common.toUserMessage
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CatalogUiState(
    val query: String = "",
    /** Tutti i risultati della ricerca, con il tipo noto o UNKNOWN. */
    val items: List<SearchResult> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val hasSearched: Boolean = false,
    val isConfigured: Boolean = true,
    /** Quanti risultati stiamo ancora classificando come film/serie. */
    val classifying: Int = 0,
) {
    /** In "Film" mostriamo film + non ancora classificati; idem per "Serie TV". */
    fun visibleFor(target: ContentType): List<SearchResult> =
        items.filter { it.type == target || it.type == ContentType.UNKNOWN }
}

/**
 * ViewModel principale delle sezioni Film e Serie TV.
 * - ricerca con debounce mentre si digita sulla tastiera a schermo (600 ms, min 2 caratteri)
 * - classificazione film/serie in background per i risultati senza tipo
 * - memoria dell'ultimo elemento focalizzato, per ripristinare il focus al ritorno dal Dettaglio
 */
@OptIn(FlowPreview::class)
class CatalogViewModel(
    private val target: ContentType,
    private val vod: VodRepository,
    settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CatalogUiState())
    val state: StateFlow<CatalogUiState> = _state.asStateFlow()

    private val queryInput = MutableStateFlow("")
    private var searchJob: Job? = null
    private var lastSearched: String? = null

    var lastFocusedSlug: String? = null
        private set

    init {
        viewModelScope.launch {
            settings.settings.collect { s -> _state.update { it.copy(isConfigured = s.isConfigured) } }
        }
        viewModelScope.launch {
            queryInput
                .debounce(DEBOUNCE_MS)
                .map { it.trim() }
                .filter { it.length >= MIN_QUERY_LENGTH }
                .collect { search(it, force = false) }
        }
    }

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        queryInput.value = query
    }

    /** Tasto "Cerca" o invio sulla tastiera: forza la ricerca anche se la query non è cambiata. */
    fun submit() {
        val q = _state.value.query.trim()
        if (q.isNotEmpty()) search(q, force = true)
    }

    fun retry() = submit()

    fun onItemFocused(slug: String) {
        lastFocusedSlug = slug
    }

    private fun search(query: String, force: Boolean) {
        if (!force && query == lastSearched) return
        lastSearched = query
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            vod.search(query)
                .onSuccess { results ->
                    _state.update { it.copy(isLoading = false, hasSearched = true, items = results) }
                    lastFocusedSlug = null
                    classifyUnknown(results)
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(isLoading = false, hasSearched = true, items = emptyList(), error = e.toUserMessage())
                    }
                }
        }
    }

    /** Figlia del job di ricerca: una nuova ricerca annulla anche le classificazioni in corso. */
    private suspend fun classifyUnknown(results: List<SearchResult>) = coroutineScope {
        val unknown = results.filter { it.type == ContentType.UNKNOWN }
        if (unknown.isEmpty()) return@coroutineScope
        _state.update { it.copy(classifying = unknown.size) }
        unknown.forEach { result ->
            launch {
                val type = vod.classify(result.slug)
                _state.update { s ->
                    s.copy(
                        items = s.items.map { if (it.slug == result.slug) it.copy(type = type) else it },
                        classifying = (s.classifying - 1).coerceAtLeast(0),
                    )
                }
            }
        }
    }

    companion object {
        private const val DEBOUNCE_MS = 600L
        private const val MIN_QUERY_LENGTH = 2

        fun factory(target: ContentType) = appViewModelFactory { c, _ ->
            CatalogViewModel(target, c.vodRepository, c.settingsRepository)
        }
    }
}
