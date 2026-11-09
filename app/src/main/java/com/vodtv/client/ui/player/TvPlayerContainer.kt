package com.vodtv.client.ui.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.ui.PlayerView

/**
 * Contenitore della PlayerView che intercetta i tasti del telecomando PRIMA della PlayerView.
 * (Un ViewGroup riceve dispatchKeyEvent prima dei figli: è il punto più affidabile.)
 *
 * Comandi a controlli nascosti:  ◀ ▶ = -/+10 s · OK = play/pausa · ▲ ▼ = mostra controlli
 * Tasti media sempre attivi:     Play/Pausa, Play, Pausa, Avanti veloce, Indietro veloce, Stop
 * Indietro (Back):               se i controlli sono visibili li chiude, altrimenti esce dal player
 * A controlli visibili il D-pad naviga fra i pulsanti della PlayerView (comportamento standard).
 */
@UnstableApi
@SuppressLint("ViewConstructor")
class TvPlayerContainer(context: Context) : FrameLayout(context) {

    val playerView: PlayerView = PlayerView(context).apply {
        useController = true
        controllerAutoShow = true
        controllerShowTimeoutMs = 4_000
        controllerHideOnTouch = false
        setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        setShowFastForwardButton(true)
        setShowRewindButton(true)
        setShowNextButton(false)
        setShowPreviousButton(false)
        setShowSubtitleButton(true)
        keepScreenOn = true
    }

    var onControllerVisibilityChanged: ((Boolean) -> Unit)? = null

    private var consumeBackUp = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.BLACK)
        addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                val visible = visibility == View.VISIBLE
                onControllerVisibilityChanged?.invoke(visible)
                // Controlli nascosti: il focus torna al contenitore, così i tasti passano ancora da qui
                if (!visible) post { requestFocus() }
            },
        )
    }

    fun bind(player: Player) {
        if (playerView.player !== player) playerView.player = player
    }

    fun unbind() {
        playerView.player = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { requestFocus() }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val player = playerView.player ?: return super.dispatchKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        val firstDown = down && event.repeatCount == 0
        val controlsVisible = playerView.isControllerFullyVisible

        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (down && controlsVisible) {
                    playerView.hideController()
                    consumeBackUp = true
                    return true
                }
                if (!down && consumeBackUp) {
                    consumeBackUp = false
                    return true
                }
                return false // lascia gestire all'Activity: esce dal player
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                if (firstDown) { Util.handlePlayPauseButtonAction(player); showControls() }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (firstDown) { Util.handlePlayButtonAction(player); showControls() }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> {
                if (firstDown) { Util.handlePauseButtonAction(player); showControls() }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (down) { seekForward(player); showControls() }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (down) { seekBack(player); showControls() }
                return true
            }
        }

        if (!controlsVisible && isDpadKey(event.keyCode)) {
            if (down) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> seekBack(player)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> seekForward(player)
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                        if (firstDown) Util.handlePlayPauseButtonAction(player)
                }
                showControls()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showControls() {
        playerView.showController()
        post {
            val focused = findFocus()
            if (focused == null || focused === this) playerView.requestFocus()
        }
    }

    private fun seekForward(player: Player) {
        if (player.isCommandAvailable(Player.COMMAND_SEEK_FORWARD)) player.seekForward()
    }

    private fun seekBack(player: Player) {
        if (player.isCommandAvailable(Player.COMMAND_SEEK_BACK)) player.seekBack()
    }

    private fun isDpadKey(keyCode: Int) = keyCode in DPAD_KEYS

    private companion object {
        val DPAD_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        )
    }
}
