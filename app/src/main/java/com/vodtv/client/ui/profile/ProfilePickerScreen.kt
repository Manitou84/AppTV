package com.vodtv.client.ui.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.vodtv.client.data.profile.Profile
import com.vodtv.client.data.profile.ProfileRepository
import com.vodtv.client.ui.common.appViewModelFactory
import com.vodtv.client.ui.components.LoadingBox
import com.vodtv.client.ui.theme.VodColors
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PickerUiState(
    val profiles: List<Profile>? = null,
    val activeId: String? = null,
    /** Profilo standard scelto da un profilo bambini: serve il PIN. */
    val pinTarget: Profile? = null,
    val pinError: String? = null,
)

class ProfilePickerViewModel(private val repo: ProfileRepository) : ViewModel() {

    private val pin = MutableStateFlow<Pair<Profile?, String?>>(null to null)

    val state: StateFlow<PickerUiState> = combine(repo.profiles, repo.active, pin) { list, active, p ->
        PickerUiState(profiles = list, activeId = active?.id, pinTarget = p.first, pinError = p.second)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PickerUiState())

    private val _done = Channel<Unit>(Channel.BUFFERED)
    val done: Flow<Unit> = _done.receiveAsFlow()

    /** Da bambini a standard serve il PIN; tutti gli altri passaggi sono liberi. */
    fun select(target: Profile) {
        val current = repo.active.value
        if (current?.isKid == true && !target.isKid && repo.hasPin.value) {
            pin.value = target to null
        } else {
            switch(target)
        }
    }

    fun submitPin(value: String) {
        val target = pin.value.first ?: return
        viewModelScope.launch {
            if (repo.verifyPin(value)) {
                pin.value = null to null
                switch(target)
            } else {
                pin.update { it.first to "PIN errato, riprova." }
            }
        }
    }

    fun cancelPin() {
        pin.value = null to null
    }

    private fun switch(target: Profile) {
        viewModelScope.launch {
            repo.switchTo(target.id)
            _done.send(Unit)
        }
    }

    companion object {
        val Factory = appViewModelFactory { c, _ -> ProfilePickerViewModel(c.profileRepository) }
    }
}

/**
 * "Chi guarda?": all'avvio (se ci sono più profili) e dal pulsante profilo nella barra in alto.
 * Con un solo profilo, all'avvio entra subito.
 */
@Composable
fun ProfilePickerScreen(
    startup: Boolean,
    onDone: () -> Unit,
    viewModel: ProfilePickerViewModel = viewModel(factory = ProfilePickerViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) { viewModel.done.collect { onDone() } }

    val profiles = state.profiles
    if (profiles.isNullOrEmpty()) {
        LoadingBox()
        return
    }
    LaunchedEffect(profiles.size) {
        if (startup && profiles.size == 1) viewModel.select(profiles.first())
    }
    if (startup && profiles.size == 1) {
        LoadingBox()
        return
    }

    val activeFocus = remember { FocusRequester() }
    val activeIndex = profiles.indexOfFirst { it.id == state.activeId }.coerceAtLeast(0)

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(40.dp)) {
            Text("Chi guarda?", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = VodColors.Text)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(36.dp),
                contentPadding = PaddingValues(horizontal = 64.dp, vertical = 16.dp),
            ) {
                itemsIndexed(profiles, key = { _, p -> p.id }) { index, profile ->
                    ProfileTile(
                        profile = profile,
                        onClick = { viewModel.select(profile) },
                        modifier = if (index == activeIndex) Modifier.focusRequester(activeFocus) else Modifier,
                    )
                }
            }
            Text("Profili e PIN si gestiscono in Impostazioni da un profilo standard.", fontSize = 15.sp, color = VodColors.TextMuted)
        }
    }
    LaunchedEffect(profiles.size) { runCatching { activeFocus.requestFocus() } }

    state.pinTarget?.let { target ->
        PinPadDialog(
            title = "PIN genitori",
            subtitle = "Inserisci il PIN per passare a «${target.name}»",
            error = state.pinError,
            onComplete = viewModel::submitPin,
            onDismiss = viewModel::cancelPin,
        )
    }
}

@Composable
private fun ProfileTile(profile: Profile, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(
        modifier = Modifier.width(150.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Surface(
            onClick = onClick,
            interactionSource = interaction,
            modifier = modifier,
            shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(BorderStroke(5.dp, VodColors.Text), shape = CircleShape),
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        ) {
            ProfileAvatar(profile, size = 130.dp, modifier = Modifier.padding(6.dp))
        }
        Text(
            profile.name,
            fontSize = 20.sp,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
            color = if (focused) Color.White else VodColors.TextSoft,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (profile.isKid) Text("Bambini", fontSize = 14.sp, color = VodColors.Accent)
    }
}
