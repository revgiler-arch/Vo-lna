package com.volna.music

import com.volna.music.data.Yt
import com.volna.music.net.OkDownloader
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import java.io.File

/** Диагностика: что отдаёт поиск YouTube Music при разных языках/регионах. */
class ProbeTest {
    @Test
    fun probe() {
        val out = StringBuilder()
        val combos = listOf("ru" to "AZ", "az" to "AZ", "en" to "AZ", "ru" to "RU", "en" to "US", "ru" to "US")
        val queries = listOf("популярные песни", "Imagine Dragons Believer", "Miyagi")
        for ((lang, country) in combos) {
            NewPipe.init(OkDownloader.instance, Localization(lang, country), ContentCountry(country))
            for (q in queries) {
                val line = try {
                    val yt = ServiceList.YouTube
                    val qh = yt.searchQHFactory.fromQuery(q, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "")
                    val info = SearchInfo.getInfo(yt, qh)
                    val raw = info.relatedItems
                    val tracks = runBlocking { Yt.searchSongs(q) }
                    "raw=${raw.size} tracks=${tracks.size} errors=${info.errors.size} " +
                        info.errors.take(2).joinToString { it.toString().take(200) } +
                        " first=" + tracks.take(2).joinToString { "${it.title}/${it.artist}/${it.durationSec}" } +
                        " rawFirst=" + raw.take(2).joinToString { "${it.javaClass.simpleName}:${it.name}" }
                } catch (e: Throwable) {
                    "EXCEPTION ${e.javaClass.name}: ${e.message?.take(300)}"
                }
                out.append("$lang-$country | $q | $line\n")
            }
            // радио и обычный поиск видео — для сравнения
            try {
                val yt = ServiceList.YouTube
                val qh = yt.searchQHFactory.fromQuery("популярные песни", listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "")
                out.append("$lang-$country | VIDEOS | ${SearchInfo.getInfo(yt, qh).relatedItems.size}\n")
            } catch (e: Throwable) { out.append("$lang-$country | VIDEOS | EXC ${e.message?.take(200)}\n") }
        }
        File("build/probe.txt").apply { parentFile?.mkdirs() }.writeText(out.toString())
        println(out)
    }

    /** Проверка перед автопубликацией: поиск должен находить песни. */
    @Test
    fun searchWorks() {
        NewPipe.init(OkDownloader.instance, Localization("en", "US"), ContentCountry("US"))
        val tracks = runBlocking { Yt.searchSongs("Imagine Dragons Believer") }
        check(tracks.size >= 5) { "Поиск вернул слишком мало: ${tracks.size}" }
        check(tracks.any { it.title.contains("Believer", ignoreCase = true) }) { "Нет ожидаемого трека" }
        val radio = runBlocking { Yt.radio(tracks.first().id) }
        check(radio.isNotEmpty()) { "Радио пустое" }
    }
}
