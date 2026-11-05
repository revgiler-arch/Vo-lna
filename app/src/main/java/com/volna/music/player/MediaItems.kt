package com.volna.music.player

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.annotation.OptIn
import com.volna.music.data.Track

/** Трек ⇄ MediaItem. Ссылку на поток получаем лениво при воспроизведении (ytm://track/<id>). */
@OptIn(UnstableApi::class)
object MediaItems {
    const val SCHEME = "ytm"

    fun uriFor(id: String): Uri = Uri.parse("$SCHEME://track/$id")

    fun of(t: Track): MediaItem = build(
        t.id,
        MediaMetadata.Builder()
            .setTitle(t.title)
            .setArtist(t.artist)
            .setArtworkUri(t.thumb?.let(Uri::parse))
            .setExtras(Bundle().apply {
                putString("artistUrl", t.artistUrl)
                putString("thumb", t.thumb)
                putLong("dur", t.durationSec)
            })
            .build(),
    )

    /** Пересобрать элемент, пришедший от контроллера (uri контроллер не передаёт). */
    fun rebuild(m: MediaItem): MediaItem = build(m.mediaId, m.mediaMetadata)

    private fun build(id: String, meta: MediaMetadata) = MediaItem.Builder()
        .setMediaId(id)
        .setUri(uriFor(id))
        .setCustomCacheKey(id)
        .setMediaMetadata(meta)
        .build()

    fun track(m: MediaItem): Track {
        val md = m.mediaMetadata
        val ex = md.extras
        return Track(
            id = m.mediaId,
            title = md.title?.toString() ?: "",
            artist = md.artist?.toString() ?: "",
            artistUrl = ex?.getString("artistUrl"),
            thumb = ex?.getString("thumb") ?: md.artworkUri?.toString(),
            durationSec = ex?.getLong("dur") ?: 0L,
        )
    }
}
