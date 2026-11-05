package com.volna.music.player

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.volna.music.App
import com.volna.music.data.Recommender
import com.volna.music.data.Yt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Фоновое воспроизведение: уведомление, кнопки на экране блокировки и в наушниках —
 * всё это даёт MediaSessionService из Media3.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val scope = MainScope()
    private val db get() = App.instance.db

    private var radioJob: Job? = null
    private val retried = HashSet<String>()

    // учёт прослушивания текущего трека
    private var playRow = -1L
    private var playDurationSec = 0L
    private var listenedMs = 0L
    private var playingSince = 0L

    override fun onCreate() {
        super.onCreate()
        com.volna.music.Diag.mark(this, "service:create")

        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val resolving = ResolvingDataSource.Factory(http, StreamResolver)
        val cached = CacheDataSource.Factory()
            .setCache(AudioCache.get(this))
            .setUpstreamDataSourceFactory(resolving)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(cached))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true, // ставим на паузу при звонке / другом плеере
            )
            .setHandleAudioBecomingNoisy(true) // пауза, когда выдернули наушники
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(listener)
        com.volna.music.Diag.mark(this, "service:player ok")

        val open = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setCallback(callback)
            .setSessionActivity(open)
            .build()
        com.volna.music.Diag.mark(this, "service:session ok")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // смахнули приложение: если музыка не играет — закрываемся
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        finishCurrentPlay()
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private val callback = object : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map(MediaItems::rebuild).toMutableList())
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            finishCurrentPlay()
            if (mediaItem != null) startPlay(mediaItem)
            extendRadioIfNeeded()
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            extendRadioIfNeeded()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val now = System.currentTimeMillis()
            if (isPlaying) {
                playingSince = now
            } else if (playingSince > 0) {
                listenedMs += now - playingSince
                playingSince = 0
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val id = player.currentMediaItem?.mediaId ?: return
            StreamResolver.invalidate(id)
            // при блокировке IP следующие треки тоже не сыграют — не перебираем очередь впустую
            if (com.volna.music.ui.isBotBlock(error)) return
            if (retried.add(id)) {
                // ссылка могла протухнуть — получаем новую и пробуем ещё раз
                player.prepare()
                player.play()
            } else if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }
    }

    private fun startPlay(item: MediaItem) {
        val track = MediaItems.track(item)
        playDurationSec = track.durationSec
        listenedMs = 0
        playingSince = if (player.isPlaying) System.currentTimeMillis() else 0
        // запись в локальную SQLite — доли миллисекунды, делаем сразу
        db.saveTrack(track)
        playRow = db.addPlay(track.id)
    }

    private fun finishCurrentPlay() {
        val row = playRow
        if (row < 0) return
        if (playingSince > 0) {
            listenedMs += System.currentTimeMillis() - playingSince
            playingSince = System.currentTimeMillis()
        }
        val listened = listenedMs
        // пропуск = выключили в первые 30 секунд (и это не очень короткий трек)
        val skipped = listened < 30_000 && (playDurationSec == 0L || playDurationSec > 45)
        playRow = -1
        db.finishPlay(row, listened, skipped)
    }

    /** Когда в очереди осталось ≤ 2 трека — дозаливаем радио по текущему. */
    private fun extendRadioIfNeeded() {
        if (player.mediaItemCount == 0) return
        if (player.repeatMode != Player.REPEAT_MODE_OFF) return
        val left = player.mediaItemCount - 1 - player.currentMediaItemIndex
        if (left > 2 || radioJob?.isActive == true) return
        val seed = player.currentMediaItem?.mediaId ?: return

        radioJob = scope.launch {
            val queued = (0 until player.mediaItemCount)
                .map { player.getMediaItemAt(it).mediaId }.toSet()
            val add = withContext(Dispatchers.IO) {
                val radio = runCatching { Yt.radio(seed) }.getOrDefault(emptyList())
                Recommender.filterForQueue(db, radio, queued).take(15)
            }
            if (add.isNotEmpty()) player.addMediaItems(add.map(MediaItems::of))
        }
    }
}
