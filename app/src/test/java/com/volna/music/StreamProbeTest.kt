package com.volna.music

import com.volna.music.data.Yt
import com.volna.music.data.YtFallback
import com.volna.music.net.OkDownloader
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File

/** Диагностика потоков: основной клиент и запасные (с серверов GitHub, где YouTube часто требует «не бот»). */
class StreamProbeTest {
    private val http = OkHttpClient()

    private fun check(url: String, ua: String): String =
        runCatching {
            http.newCall(Request.Builder().url(url).header("User-Agent", ua).header("Range", "bytes=0-1").build())
                .execute().use { "HTTP ${it.code}" }
        }.getOrElse { "ERR ${it.message}" }

    private fun ua(url: String): String = YtFallback.userAgentFor(url)
        ?: if (org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isVisionOsStreamingUrl(url)) {
            org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getVisionOsUserAgent(null)
        } else OkDownloader.USER_AGENT

    @Test
    fun probe() {
        NewPipe.init(OkDownloader.instance, Localization("ru", "AZ"), ContentCountry("AZ"))
        val out = StringBuilder()
        for (id in listOf("7wtfhZwyrcc", "kJQP7kiw5Fk", "dQw4w9WgXcQ")) {
            val main = runCatching {
                val info = StreamInfo.getInfo(ServiceList.YouTube, Yt.watchUrl(id))
                val a = info.audioStreams.firstOrNull { it.isUrl }?.content
                "audio=${info.audioStreams.size} " + (a?.let { check(it, ua(it)) } ?: "-")
            }.getOrElse { "EXC ${it.javaClass.simpleName}: ${it.message?.take(160)}" }
            out.append("$id | main(visionOS) | $main\n")

            val fb = runCatching {
                val s = YtFallback.streams(id)
                "${s.client} audio=${check(s.audio, ua(s.audio))} " +
                    "video=${s.video?.let { check(it, ua(it)) } ?: "нет"}"
            }.getOrElse { "EXC ${it.message?.take(300)}" }
            out.append("$id | fallback | $fb\n")
            out.append("$id | report | ${YtFallback.lastReport}\n")
            YtFallback.forget(id)

            val chain = runCatching { Yt.resolveAudioUrl(id).take(60) }.getOrElse { "EXC ${it.message?.take(160)}" }
            out.append("$id | resolveAudioUrl | $chain\n")
        }
        File("build/stream-probe.txt").apply { parentFile?.mkdirs() }.writeText(out.toString())
        println(out)
    }
}
