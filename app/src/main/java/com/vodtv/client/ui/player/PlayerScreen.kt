package com.vodtv.client.ui.player

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Text
import com.vodtv.client.data.playback.PlaybackSession
import com.vodtv.client.ui.components.ButtonTextPadding
import com.vodtv.client.ui.components.PrimaryButton
import com.vodtv.client.ui.components.SecondaryButton
import com.vodtv.client.ui.theme.VodColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Player a tutto schermo: ExoPlayer (Media3) + PlayerView con controlli D-pad.
 * Salva l'avanzamento ogni 10 s e all'uscita; a fine episodio propone il successivo.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    onExit: () -> Unit,
    viewModel: PlayerViewModel = viewModel(factory = PlayerViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val session = state.session

    LaunchedEffect(session, state.finished) {
        if (session == null || state.finished) onExit()
    }
    if (session == null) return

    // Una nuova sessione (es. episodio successivo) ricrea il player
    key(session.progressKey, session.stream.url) {
        PlayerSurface(session = session, viewModel = viewModel, onExit = onExit)
    }

    state.nextUp?.let { next ->
        NextUpOverlay(
            title = "S${next.ref.season} · E${next.ref.episode} · ${next.ref.name}",
            secondsLeft = next.secondsLeft,
            loading = state.loadingNext,
            error = state.nextError,
            onPlayNow = viewModel::playNext,
            onCancel = viewModel::cancelNext,
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerSurface(session: PlaybackSession, viewModel: PlayerViewModel, onExit: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    var container by remember { mutableStateOf<TvPlayerContainer?>(null) }

    val player: ExoPlayer = remember { TvPlayerFactory.create(context, session) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                } else {
                    errorMessage = error.toUserMessage()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && !session.isLive) {
                    viewModel.onPlaybackEnded(player.duration)
                }
            }
        }
        player.addListener(listener)
        player.prepare()
        player.playWhenReady = true

        onDispose {
            // Ultimo salvataggio prima di rilasciare il player
            if (player.playbackState != Player.STATE_ENDED) {
                viewModel.saveProgress(player.currentPosition, player.duration)
            }
            player.removeListener(listener)
            player.release()
        }
    }

    // Salvataggio periodico dell'avanzamento
    LaunchedEffect(player) {
        while (isActive) {
            delay(PROGRESS_SAVE_INTERVAL_MS)
            if (player.isPlaying) viewModel.saveProgress(player.currentPosition, player.duration)
        }
    }

    // App in background (tasto Home): pausa e salvataggio
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                player.pause()
                viewModel.saveProgress(player.currentPosition, player.duration)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                TvPlayerContainer(ctx).apply {
                    bind(player)
                    onControllerVisibilityChanged = { controlsVisible = it }
                    container = this
                }
            },
            update = { it.bind(player) },
            onRelease = { it.unbind() },
            modifier = Modifier.fillMaxSize(),
        )

        // Titolo in alto, visibile insieme ai controlli
        AnimatedVisibility(
            visible = controlsVisible && errorMessage == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
                    .padding(start = 64.dp, end = 64.dp, top = 36.dp, bottom = 48.dp),
            ) {
                Text(session.title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                session.subtitle?.let { Text(it, fontSize = 16.sp, color = VodColors.TextSoft) }
                if (session.isLive) Text("IN DIRETTA", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = VodColors.Live)
            }
        }

        errorMessage?.let { message ->
            ErrorOverlay(
                message = message,
                onRetry = {
                    errorMessage = null
                    player.prepare()
                    player.play()
                    container?.requestFocus()
                },
                onExit = onExit,
            )
        }
    }
}

@Composable
private fun ErrorOverlay(message: String, onRetry: () -> Unit, onExit: () -> Unit) {
    val retryFocus = remember { FocusRequester() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text(message, fontSize = 20.sp, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 720.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PrimaryButton(onClick = onRetry, modifier = Modifier.focusRequester(retryFocus)) {
                    Text("Riprova", modifier = ButtonTextPadding)
                }
                SecondaryButton(onClick = onExit) { Text("Indietro", modifier = ButtonTextPadding) }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { retryFocus.requestFocus() } }
}

@Composable
private fun NextUpOverlay(
    title: String,
    secondsLeft: Int,
    loading: Boolean,
    error: String?,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
) {
    val playFocus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        Column(
            modifier = Modifier
                .padding(64.dp)
                .widthIn(max = 520.dp)
                .background(VodColors.Surface.copy(alpha = 0.95f), RoundedCornerShape(18.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Prossimo episodio", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = VodColors.TextMuted)
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2)
            Text(
                text = when {
                    error != null -> error
                    loading -> "Carico il flusso…"
                    else -> "Inizia tra $secondsLeft s"
                },
                fontSize = 16.sp,
                color = if (error != null) VodColors.Live else VodColors.TextSoft,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(onClick = onPlayNow, modifier = Modifier.focusRequester(playFocus)) {
                    Text("Riproduci ora", modifier = ButtonTextPadding)
                }
                SecondaryButton(onClick = onCancel) { Text("Annulla", modifier = ButtonTextPadding) }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }
}

@OptIn(UnstableApi::class)
private fun PlaybackException.toUserMessage(): String {
    val http = cause as? HttpDataSource.InvalidResponseCodeException
    return when {
        http != null && http.responseCode in listOf(401, 403) ->
            "Accesso al flusso negato (HTTP ${http.responseCode}). Il link potrebbe essere scaduto: torna indietro e riprova."
        http != null -> "Il server video ha risposto con HTTP ${http.responseCode}."
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "Connessione al flusso non riuscita."
        errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
            errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
            "Il flusso HLS non è valido."
        errorCode == PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED ||
            errorCode == PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION ->
            "Licenza del contenuto protetto non concessa."
        errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
            errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
            "Formato video non supportato da questo dispositivo."
        else -> "Errore di riproduzione ($errorCodeName)."
    }
}

private const val PROGRESS_SAVE_INTERVAL_MS = 10_000L
