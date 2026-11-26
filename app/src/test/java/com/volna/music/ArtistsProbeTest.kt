package com.volna.music

import com.volna.music.data.Artists
import com.volna.music.data.Track
import com.volna.music.net.OkDownloader
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.io.File

/** Проверка «похожих исполнителей» на настоящем YouTube Music. */
class ArtistsProbeTest {
    @Test
    fun probe() = runBlocking {
        NewPipe.init(OkDownloader.instance, Localization("ru", "AZ"), ContentCountry("AZ"))
        val out = StringBuilder()
        val t1 = Track("x", "MATRANG - Медуза", "Koala production", null, null, 0)
        val t2 = Track("y", "Доча", "Jah Khalib", null, null, 0)
        val t3 = Track("z", "Патрон", "Miyagi & Andy Panda", null, null, 0)
        out.append("candidates: ${Artists.candidates(t1)} ${Artists.candidates(t2)} ${Artists.candidates(t3)}\n")
        for (q in listOf("стима", "стим", "Стим рэп")) {
            val yt = org.schabi.newpipe.extractor.ServiceList.YouTube
            val qh = yt.searchQHFactory.fromQuery(q, listOf(org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS), "")
            val items = runCatching { org.schabi.newpipe.extractor.search.SearchInfo.getInfo(yt, qh).relatedItems }.getOrDefault(emptyList())
            out.append("search $q: ${items.take(6).joinToString { it.name + " [" + ((it as? org.schabi.newpipe.extractor.channel.ChannelInfoItem)?.subscriberCount ?: -1) + "]" }}\n")
        }
        for (q in listOf("стима", "Земфира", "skillet", "гуф", "Jah Khalib", "MATRANG", "грустные песни")) {
            val ref = runCatching { Artists.lookup(q) }.getOrElse { out.append("$q: EXC ${it.message}\n"); null }
            if (ref == null) { out.append("$q → нет исполнителя\n"); continue }
            val page = runCatching { Artists.page(ref) }.getOrElse { out.append("$q: page EXC ${it}\n"); null }
            out.append("$q → ${ref.name} (${ref.id}) | page=${page?.ref?.name} songs=${page?.songs?.size} ")
            out.append("примеры: ${page?.songs?.take(3)?.joinToString { it.artist + " – " + it.title }} | ")
            out.append("похожие: ${page?.similar?.joinToString { it.name }}\n")
        }
        File("build/stream-probe.txt").apply { parentFile?.mkdirs() }.writeText(out.toString())
        println(out)
    }
}
