package com.volna.music.data

import android.content.Context
import com.volna.music.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Исполнители и «похожие исполнители» из YouTube Music.
 *
 * Имя исполнителя → его страница в YouTube Music (найденные соответствия запоминаем навсегда,
 * страницы — на несколько часов). На странице есть блок «Похожие исполнители» — на нём
 * и строятся рекомендации: слушаете Земфиру — в подборку идут Би-2 и Сплин.
 */
object Artists {
    private const val PAGE_TTL = 6 * 3_600_000L
    private const val MISS_TTL = 6 * 3_600_000L

    private val prefs by lazy { App.instance.getSharedPreferences("artists", Context.MODE_PRIVATE) }
    private val pages = ConcurrentHashMap<String, Pair<ArtistPage, Long>>()
    private val misses = ConcurrentHashMap<String, Long>()
    private val net = Semaphore(6)

    // ---------- имя исполнителя ----------

    private val CHANNEL_WORDS = Regex(
        """music|muzik|muzyka|музык|production|records|recordz|label|official|lyrics|hits|channel|media|studio|tv\b|\bfm\b|""" +
            """sound|beats|remix|cover|караоке|vevo""",
        RegexOption.IGNORE_CASE,
    )
    private val DASH = Regex("""\s[-–—]\s""")
    private val SPLIT = Regex("""\s*(?:,|&|/|\+|\s(?:feat\.?|ft\.?|x|х|×|vs\.?)\s)\s*""", RegexOption.IGNORE_CASE)

    /** Первый исполнитель из «A & B feat. C». */
    fun firstName(raw: String): String =
        raw.split(SPLIT).map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: raw.trim()

    /**
     * Возможные имена исполнителя трека, лучшее первым. Загруженное каналом-перезаливщиком
     * («AFSUN MUSIC», «Koala production») подписано в названии: «MATRANG - Медуза».
     */
    fun candidates(t: Track): List<String> {
        val uploader = firstName(t.artist)
        val dash = DASH.find(t.title)
        val left = if (dash != null && dash.range.first in 2..60) firstName(t.title.substring(0, dash.range.first)) else null
        val list = when {
            left == null -> listOf(uploader)
            uploader.isBlank() -> listOf(left)
            CHANNEL_WORDS.containsMatchIn(t.artist) -> listOf(left, uploader)
            norm(left).contains(norm(uploader)) || norm(uploader).contains(norm(left)) -> listOf(uploader)
            else -> listOf(left, uploader)
        }
        return list.filter { norm(it).length >= 2 }.distinctBy { norm(it) }
    }

    /** Имя, по которому трек относится к исполнителю (для «вкуса» и разнообразия ленты). */
    fun primary(t: Track): String = candidates(t).firstOrNull() ?: t.artist

    /** Ключ для сравнения имён: нижний регистр, латиница, без знаков («Стим» = «STIM»). */
    fun norm(s: String): String {
        val sb = StringBuilder()
        for (ch in s.lowercase()) {
            val t = TRANSLIT[ch]
            when {
                t != null -> sb.append(t)
                ch.isLetterOrDigit() -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private val TRANSLIT: Map<Char, String> = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e", 'ж' to "zh",
        'з' to "z", 'и' to "i", 'й' to "i", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o",
        'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "c",
        'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu",
        'я' to "ya", 'ə' to "e", 'ı' to "i", 'ö' to "o", 'ü' to "u", 'ç' to "ch", 'ş' to "sh", 'ğ' to "g",
    )

    /** Похожи ли имена: «стима» ≈ «Стим», «skillet» ≈ «Skillet», «би2» ≈ «Би-2». */
    fun sameName(a: String, b: String): Boolean {
        val x = norm(a)
        val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        val short = minOf(x.length, y.length)
        if (short >= 3 && (x.startsWith(y) || y.startsWith(x)) && kotlin.math.abs(x.length - y.length) <= 2) return true
        if (short >= 4 && (x.contains(y) || y.contains(x))) return true
        return short >= 4 && distance(x, y) <= maxOf(1, short / 4)
    }

    private fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    // ---------- поиск исполнителя ----------

    /** Найти исполнителя в YouTube Music по имени (или по запросу из поиска). null — не похоже ни на кого. */
    suspend fun lookup(name: String): ArtistRef? = withContext(Dispatchers.IO) {
        val key = norm(name)
        if (key.length < 2) return@withContext null
        runCatching { prefs.getString(key, null) }.getOrNull()?.let { s ->
            return@withContext ArtistRef(s.substringAfter('|'), s.substringBefore('|'))
        }
        misses[key]?.let { if (System.currentTimeMillis() - it < MISS_TTL) return@withContext null }
        // сетевая ошибка — не запоминаем, попробуем в следующий раз
        val found = net.withPermit { runCatching { searchArtists(name) }.getOrNull() } ?: return@withContext null
        val hit = found.take(5).firstOrNull { sameName(name, it.name) }
        if (hit == null) {
            misses[key] = System.currentTimeMillis()
            return@withContext null
        }
        runCatching { prefs.edit().putString(key, "${hit.id}|${hit.name}").apply() }
        hit
    }

    /** Исполнитель трека: пробуем имя из названия и имя канала. */
    suspend fun of(t: Track): ArtistRef? {
        for (c in candidates(t)) lookup(c)?.let { return it }
        return null
    }

    private fun searchArtists(q: String): List<ArtistRef> {
        val yt = ServiceList.YouTube
        val qh = yt.searchQHFactory.fromQuery(q, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS), "")
        return SearchInfo.getInfo(yt, qh).relatedItems.filterIsInstance<ChannelInfoItem>().mapNotNull { item ->
            val id = Regex("""UC[\w-]{22}""").find(item.url ?: "")?.value ?: return@mapNotNull null
            ArtistRef(item.name ?: "", id)
        }
    }

    // ---------- страница исполнителя ----------

    suspend fun page(ref: ArtistRef): ArtistPage? = withContext(Dispatchers.IO) {
        pages[ref.id]?.let { (p, at) -> if (System.currentTimeMillis() - at < PAGE_TTL) return@withContext p }
        val p = net.withPermit { runCatching { YtAccount.artistPage(ref.id) }.getOrNull() } ?: return@withContext null
        val fixed = if (p.ref.name.isBlank()) ArtistPage(ref, p.songs, p.similar) else p
        if (pages.size > 300) pages.clear()
        pages[ref.id] = fixed to System.currentTimeMillis()
        fixed
    }

    suspend fun pages(refs: List<ArtistRef>): List<ArtistPage> = coroutineScope {
        refs.distinctBy { it.id }.map { async { page(it) } }.awaitAll().filterNotNull()
    }
}
