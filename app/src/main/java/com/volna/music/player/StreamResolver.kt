package com.volna.music.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.volna.music.data.Yt
import com.volna.music.net.OkDownloader
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Превращает ytm://track/<id> в настоящую ссылку на аудиопоток прямо перед загрузкой. */
@UnstableApi
object StreamResolver : ResolvingDataSource.Resolver {

    private class Entry(val url: String, val at: Long)

    private val urls = ConcurrentHashMap<String, Entry>()
    private const val TTL = 4 * 3_600_000L // ссылки YouTube живут ~6 часов

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val uri = dataSpec.uri
        if (uri.scheme != MediaItems.SCHEME) return dataSpec
        val id = uri.lastPathSegment ?: throw IOException("bad uri $uri")
        val url = urlFor(id)
        val ua = if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) {
            YoutubeParsingHelper.getVisionOsUserAgent(null)
        } else {
            OkDownloader.USER_AGENT
        }
        return dataSpec.withUri(Uri.parse(url)).withAdditionalHeaders(mapOf("User-Agent" to ua))
    }

    private fun urlFor(id: String): String {
        urls[id]?.let { if (System.currentTimeMillis() - it.at < TTL) return it.url }
        val url = try {
            Yt.resolveAudioUrl(id)
        } catch (e: Exception) {
            throw IOException("Не удалось получить поток: ${e.message}", e)
        }
        urls[id] = Entry(url, System.currentTimeMillis())
        return url
    }

    fun invalidate(id: String) {
        urls.remove(id)
    }
}

/** Кэш уже прослушанного (300 МБ): повторы не тратят трафик. */
@UnstableApi
object AudioCache {
    @Volatile
    private var cache: SimpleCache? = null

    fun get(context: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(
            File(context.cacheDir, "audio"),
            LeastRecentlyUsedCacheEvictor(300L * 1024 * 1024),
            StandaloneDatabaseProvider(context),
        ).also { cache = it }
    }
}
