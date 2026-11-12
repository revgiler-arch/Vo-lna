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
    private var autoRetries = 0
    private var skipsInRow = 0

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

        // звук проходит через BeatTap — для темы «RGB — под музыку»
        val renderers = object : androidx.media3.exoplayer.DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): androidx.media3.exoplayer.audio.AudioSink =
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(BeatTap()))
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
        }
        player = ExoPlayer.Builder(this, renderers)
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
        watchSleepTimer()
    }

    // ---------- таймер сна ----------

    private fun watchSleepTimer() {
        scope.launch {
            SleepTimer.endOfTrack.collect { on ->
                player.pauseAtEndOfMediaItems = on
                if (!on) player.volume = 1f
            }
        }
        scope.launch {
            var fading = false
            while (true) {
                val end = SleepTimer.endAt.value
                if (end == null) {
                    if (fading) { player.volume = 1f; fading = false }
                } else {
                    val left = end - System.currentTimeMillis()
                    when {
                        left <= 0 -> {
                            SleepTimer.cancel()
                            player.pause()
                            player.volume = 1f
                            fading = false
                        }
                        left < SleepTimer.FADE_MS -> {
                            fading = true
                            player.volume = (left.toFloat() / SleepTimer.FADE_MS).coerceIn(0.05f, 1f)
                        }
                        fading -> { player.volume = 1f; fading = false }
                    }
                }
                kotlinx.coroutines.delay(if (end != null && end - System.currentTimeMillis() < SleepTimer.FADE_MS + 2000) 250 else 1000)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    // ---------- уведомление «Воспроизведение остановлено» ----------

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RESUME -> { cancelStopped(); resume() }
            ACTION_DISMISS -> cancelStopped()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun resume() {
        if (player.mediaItemCount == 0) return
        player.currentMediaItem?.mediaId?.let(StreamResolver::invalidate)
        retried.clear(); autoRetries = 0; skipsInRow = 0
        if (player.playbackState == Player.STATE_ENDED) {
            if (player.hasNextMediaItem()) player.seekToNextMediaItem() else { launchRadio(); return }
        }
        player.prepare()
        player.play()
    }

    private fun showStopped(text: String) {
        val nm = getSystemService(android.app.NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(ALERT_CHANNEL) == null) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(
                    ALERT_CHANNEL, "Остановка воспроизведения", android.app.NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "Когда музыка остановилась и нужно нажать «Продолжить»" },
            )
        }
        fun action(a: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, PlaybackService::class.java).setAction(a),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 3, packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = androidx.core.app.NotificationCompat.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(com.volna.music.R.drawable.ic_stat_volna)
            .setContentTitle("Волна: музыка остановилась")
            .setContentText(text)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Продолжить", action(ACTION_RESUME, 1))
            .addAction(0, "ОК", action(ACTION_DISMISS, 2))
            .build()
        runCatching { nm.notify(ALERT_ID, n) }
    }

    private fun cancelStopped() {
        getSystemService(android.app.NotificationManager::class.java)?.cancel(ALERT_ID)
    }

    companion object {
        const val ACTION_RESUME = "com.volna.music.RESUME"
        const val ACTION_DISMISS = "com.volna.music.DISMISS"
        private const val ALERT_CHANNEL = "alerts"
        private const val ALERT_ID = 4242
    }

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
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // «После этого трека»: плеер встал в конце трека — таймер отработал
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && SleepTimer.endOfTrack.value) {
                SleepTimer.cancel()
                if (player.hasNextMediaItem()) player.seekToNextMediaItem()
            }
        }

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
                autoRetries = 0
                skipsInRow = 0
                cancelStopped()
            } else if (playingSince > 0) {
                listenedMs += now - playingSince
                playingSince = 0
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val id = player.currentMediaItem?.mediaId ?: return
            StreamResolver.invalidate(id)
            val blocked = com.volna.music.ui.isBotBlock(error)
            com.volna.music.Diag.mark(this@PlaybackService, "player error: ${error.errorCodeName} blocked=$blocked")
            when {
                // ссылка могла протухнуть — сразу пробуем ещё раз
                !blocked && retried.add(id) -> { player.prepare(); player.play() }
                // YouTube просит «подтвердить, что не бот» — подождём и попробуем сами один раз
                blocked && autoRetries < 1 -> {
                    autoRetries++
                    scope.launch {
                        kotlinx.coroutines.delay(15_000)
                        StreamResolver.invalidate(id)
                        player.prepare(); player.play()
                    }
                }
                // этот трек не играет — пропускаем, но не больше трёх подряд
                !blocked && player.hasNextMediaItem() && skipsInRow < 3 -> {
                    skipsInRow++
                    player.seekToNextMediaItem(); player.prepare(); player.play()
                }
                else -> showStopped(
                    if (blocked) "YouTube приостановил выдачу (проверка на бота). Нажмите «Продолжить»."
                    else "Не удалось загрузить трек: ${com.volna.music.ui.describeError(error).substringBefore("\n\n")}",
                )
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state != Player.STATE_ENDED || player.repeatMode != Player.REPEAT_MODE_OFF) return
            // очередь кончилась: даём радио время догрузиться, иначе сообщаем
            scope.launch {
                kotlinx.coroutines.delay(10_000)
                if (player.playbackState == Player.STATE_ENDED) {
                    showStopped("Не удалось загрузить продолжение радио. Нажмите «Продолжить».")
                }
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
        launchRadio()
    }

    private fun launchRadio() {
        if (radioJob?.isActive == true) return
        val seed = player.currentMediaItem?.mediaId ?: return

        radioJob = scope.launch {
            val queued = (0 until player.mediaItemCount)
                .map { player.getMediaItemAt(it).mediaId }.toSet()
            val add = withContext(Dispatchers.IO) {
                val radio = runCatching { Yt.radio(seed) }.getOrDefault(emptyList())
                Recommender.filterForQueue(db, radio, queued).take(15)
            }
            if (add.isNotEmpty()) {
                player.addMediaItems(add.map(MediaItems::of))
                if (player.playbackState == Player.STATE_ENDED) {
                    player.seekToNextMediaItem(); player.prepare(); player.play()
                }
            }
        }
    }
}
