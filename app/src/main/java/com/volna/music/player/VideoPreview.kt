package com.volna.music.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.volna.music.data.Yt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Видео к треку на экране плеера. Это отдельный беззвучный плеер только для картинки:
 * музыка по-прежнему играет в фоновом сервисе, а видео лишь подгоняется под её позицию.
 * Работает только пока экран плеера открыт — в фоне видео не грузится и не тратит трафик.
 */
@OptIn(UnstableApi::class)
class VideoPreview(private val ctx: Context, private val scope: CoroutineScope) {

    enum class Status { OFF, LOADING, READY, NONE }

    var player by mutableStateOf<ExoPlayer?>(null); private set
    var status by mutableStateOf(Status.OFF); private set
    /** Ширина / высота кадра — экран подгоняет окно видео под неё. */
    var aspect by mutableFloatStateOf(16f / 9f); private set

    private var loadedId: String? = null
    private var job: Job? = null

    /** Показать видео трека [id] начиная с [positionMs]. */
    fun show(id: String, positionMs: Long, playing: Boolean) {
        if (id == loadedId && status != Status.OFF) return
        release()
        loadedId = id
        status = Status.LOADING
        job = scope.launch {
            val url = withContext(Dispatchers.IO) { runCatching { Yt.resolveVideoUrl(id) }.getOrNull() }
            if (loadedId != id) return@launch
            if (url == null) { status = Status.NONE; return@launch }

            val http = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(20_000)
                .setDefaultRequestProperties(mapOf("User-Agent" to StreamResolver.userAgentFor(url)))
            val p = ExoPlayer.Builder(ctx)
                .setMediaSourceFactory(ProgressiveMediaSource.Factory(http))
                .build()
            p.volume = 0f
            // звук из видео не нужен совсем — не декодируем его
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
            p.addListener(object : Player.Listener {
                override fun onVideoSizeChanged(size: VideoSize) {
                    if (size.width > 0 && size.height > 0) {
                        aspect = size.width * size.pixelWidthHeightRatio / size.height
                    }
                }

                override fun onRenderedFirstFrame() {
                    if (loadedId == id) status = Status.READY
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (loadedId == id) {
                        Yt.forget(id)
                        status = Status.NONE
                        scope.launch { release(keepStatus = true) }
                    }
                }
            })
            p.setMediaItem(MediaItem.fromUri(Uri.parse(url)), positionMs)
            p.playWhenReady = playing
            p.prepare()
            player = p
        }
    }

    /** Подтянуть видео к позиции музыки (вызывается пару раз в секунду). */
    fun sync(positionMs: Long, playing: Boolean) {
        val p = player ?: return
        if (p.playWhenReady != playing) p.playWhenReady = playing
        if (p.playbackState == Player.STATE_READY) {
            val drift = abs(p.currentPosition - positionMs)
            if (drift > 1_000) p.seekTo(positionMs + if (playing) 250 else 0)
        }
    }

    fun release(keepStatus: Boolean = false) {
        job?.cancel()
        job = null
        player?.release()
        player = null
        if (!keepStatus) {
            loadedId = null
            status = Status.OFF
        }
    }
}
