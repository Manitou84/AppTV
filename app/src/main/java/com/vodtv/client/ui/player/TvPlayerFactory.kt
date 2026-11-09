package com.vodtv.client.ui.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.vodtv.client.data.playback.PlaybackSession

/**
 * ExoPlayer configurato per HLS su TV:
 * - buffer generoso (i flussi da scraping hanno CDN lente o instabili)
 * - header personalizzati (Referer) e redirect http↔https consentiti
 * - Widevine se la sorgente lo richiede
 * - audio e sottotitoli in italiano preferiti
 */
@OptIn(UnstableApi::class)
object TvPlayerFactory {

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    private const val MIN_BUFFER_MS = 15_000
    private const val MAX_BUFFER_MS = 60_000
    private const val START_BUFFER_MS = 2_500
    private const val REBUFFER_MS = 5_000
    const val SEEK_STEP_MS = 10_000L

    fun create(context: Context, session: PlaybackSession): ExoPlayer {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(session.stream.headers)

        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, START_BUFFER_MS, REBUFFER_MS)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredAudioLanguage("it")
                    .setPreferredTextLanguage("it")
                    .build(),
            )
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val player = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(session.stream.url)
            // Forziamo HLS: alcuni URL di playlist non finiscono in .m3u8
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(session.displayTitle).build())
            .apply {
                session.stream.widevineLicenseUrl?.let { license ->
                    setDrmConfiguration(MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID).setLicenseUri(license).build())
                }
            }
            .build()

        val source = HlsMediaSource.Factory(dataSourceFactory)
            .setAllowChunklessPreparation(true)
            .createMediaSource(mediaItem)

        player.setMediaSource(source)
        if (session.resumeFromMs > 0) player.seekTo(session.resumeFromMs)
        return player
    }
}
