package com.volna.music.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

/** Всё общение с YouTube / YouTube Music. */
object Yt {
    private val yt get() = ServiceList.YouTube

    fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"

    /** Поиск песен в разделе YouTube Music. */
    suspend fun searchSongs(query: String): List<Track> = withContext(Dispatchers.IO) {
        val qh = yt.searchQHFactory.fromQuery(
            query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "",
        )
        val music = SearchInfo.getInfo(yt, qh)
        val tracks = music.relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull(::toTrack).distinctBy { it.id }
        if (tracks.isNotEmpty()) return@withContext tracks

        // Запасной путь: поиск YouTube Music на этом устройстве ничего не дал —
        // ищем по обычному YouTube (работает почти всегда).
        val videoQh = yt.searchQHFactory.fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "")
        val video = runCatching { SearchInfo.getInfo(yt, videoQh) }
        val videoTracks = video.getOrNull()?.relatedItems.orEmpty()
            .filterIsInstance<StreamInfoItem>().mapNotNull(::toTrack).distinctBy { it.id }
        if (videoTracks.isNotEmpty()) return@withContext videoTracks

        lastDiagnostics = buildString {
            append("music: ${music.relatedItems.size} шт., ошибок ${music.errors.size}")
            music.errors.firstOrNull()?.let { append(" (${it.javaClass.simpleName}: ${it.message?.take(120)})") }
            video.exceptionOrNull()?.let { append("; video: ${it.javaClass.simpleName}: ${it.message?.take(120)}") }
                ?: append("; video: ${video.getOrNull()?.relatedItems?.size ?: 0} шт., ошибок ${video.getOrNull()?.errors?.size ?: 0}")
        }
        music.errors.firstOrNull()?.let { throw java.io.IOException(lastDiagnostics, it) }
        video.exceptionOrNull()?.let { throw java.io.IOException(lastDiagnostics, it) }
        emptyList()
    }

    /** Что вернул YouTube в последний раз, когда поиск оказался пустым (для экрана ошибки). */
    @Volatile
    var lastDiagnostics: String? = null

    suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        runCatching { yt.suggestionExtractor.suggestionList(query) }.getOrDefault(emptyList())
    }

    /**
     * «Радио» по треку — тот же микс, что YouTube Music включает после песни.
     * Если микс недоступен, берём похожие видео.
     */
    suspend fun radio(id: String): List<Track> = withContext(Dispatchers.IO) {
        val mix = runCatching {
            PlaylistInfo.getInfo(yt, "${watchUrl(id)}&list=RDAMVM$id").relatedItems
                .mapNotNull(::toTrack)
        }.getOrDefault(emptyList())
        val list = mix.ifEmpty {
            runCatching {
                StreamInfo.getInfo(yt, watchUrl(id)).relatedItems
                    .filterIsInstance<StreamInfoItem>()
                    .mapNotNull(::toTrack)
            }.getOrDefault(emptyList())
        }
        list.filter { it.id != id }.distinctBy { it.id }
    }

    // Звук и видео одного трека берутся из одного ответа YouTube — не запрашиваем его дважды.
    private class CachedInfo(val info: StreamInfo, val at: Long)
    private val infoCache = java.util.concurrent.ConcurrentHashMap<String, CachedInfo>()
    private const val INFO_TTL = 2 * 3_600_000L

    private fun streamInfo(id: String): StreamInfo {
        infoCache[id]?.let { if (System.currentTimeMillis() - it.at < INFO_TTL) return it.info }
        val info = StreamInfo.getInfo(yt, watchUrl(id))
        if (infoCache.size > 50) infoCache.clear()
        infoCache[id] = CachedInfo(info, System.currentTimeMillis())
        return info
    }

    /** Ссылки протухли или не играют — в следующий раз спросим YouTube заново. */
    fun forget(id: String) {
        infoCache.remove(id)
    }

    /**
     * Ссылка на видео к треку (без звука — звук играет основной плеер) или null, если видео нет.
     * Берём MP4 (H.264) не выше 480p: его тянет любое устройство, включая магнитолу. Блокирующий вызов.
     */
    fun resolveVideoUrl(id: String): String? {
        val info = streamInfo(id)
        fun VideoStream.h(): Int = height.takeIf { it > 0 }
            ?: resolution.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        val only = info.videoOnlyStreams.filter {
            it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.h() in 1..480
        }
        val pick = only.filter { it.format == MediaFormat.MPEG_4 }.maxByOrNull { it.h() }
            ?: info.videoStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                .minByOrNull { kotlin.math.abs(it.h() - 360) }
            ?: only.maxByOrNull { it.h() }
        return pick?.content
    }

    /**
     * Треки для радио под занятие и настроение: ищем подходящие плейлисты YouTube Music
     * (их собирают люди под «спокойное перед сном», «бодрое для тренировки» и т.п.).
     */
    suspend fun moodTracks(queries: List<String>): List<Track> = withContext(Dispatchers.IO) {
        kotlinx.coroutines.coroutineScope {
            queries.map { q ->
                async {
                    val playlists = runCatching {
                        val qh = yt.searchQHFactory.fromQuery(
                            q, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_PLAYLISTS), "",
                        )
                        SearchInfo.getInfo(yt, qh).relatedItems.filterIsInstance<PlaylistInfoItem>().take(2)
                    }.getOrDefault(emptyList())
                    val fromPlaylists = playlists.map { p ->
                        async {
                            runCatching {
                                PlaylistInfo.getInfo(yt, p.url).relatedItems.mapNotNull(::toTrack).take(40)
                            }.getOrDefault(emptyList())
                        }
                    }.awaitAll().flatten()
                    fromPlaylists.ifEmpty { runCatching { searchSongs(q) }.getOrDefault(emptyList()) }
                }
            }.awaitAll()
        }.flatten().distinctBy { it.id }
    }

    /** Прямая ссылка на лучший аудиопоток. Блокирующий вызов. */
    fun resolveAudioUrl(id: String): String {
        val info = streamInfo(id)
        val candidates = info.audioStreams.filter {
            it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                (it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL)
        }.ifEmpty {
            info.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        }
        val best = candidates.maxByOrNull { maxOf(it.averageBitrate, it.bitrate) }
            ?: error("Нет аудиопотока для $id")
        return best.content
    }

    fun toTrack(item: StreamInfoItem): Track? {
        if (item.isShortFormContent) return null
        if (item.url.contains("/shorts/")) return null
        if (item.streamType == StreamType.LIVE_STREAM ||
            item.streamType == StreamType.AUDIO_LIVE_STREAM
        ) return null
        val d = item.duration
        if (d in 1..60) return null // короткие ролики = Shorts
        val id = runCatching { yt.streamLHFactory.getId(item.url) }.getOrNull() ?: return null
        return Track(
            id = id,
            title = item.name ?: "",
            artist = cleanArtist(item.uploaderName),
            artistUrl = item.uploaderUrl,
            thumb = bestThumb(item.thumbnails),
            durationSec = d.coerceAtLeast(0),
        )
    }

    private fun cleanArtist(name: String?): String =
        (name ?: "").removeSuffix(" - Topic").removeSuffix(" – Topic").trim()

    private fun bestThumb(list: List<Image>?): String? {
        if (list.isNullOrEmpty()) return null
        return (list.filter { it.height in 1..720 }.maxByOrNull { it.height }
            ?: list.maxByOrNull { it.height })?.url
    }
}
