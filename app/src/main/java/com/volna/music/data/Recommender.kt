package com.volna.music.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.math.pow

/**
 * Рекомендации по похожим исполнителям.
 *
 * 1. «Вкус» — какие исполнители вам нравятся: по истории (свежее весит больше, пропуски — минус),
 *    лайкам и последним поискам (запрос превращается в исполнителя: «стима» → Стим; если запрос
 *    не похож ни на одного исполнителя — он вообще не влияет на подборку).
 * 2. Для нескольких любимых исполнителей берём из YouTube Music их «Похожих исполнителей».
 * 3. Лента — в основном песни похожих исполнителей (то, что вы ещё не слушали — выше),
 *    плюс немного любимых и чуть-чуть радио по треку. Один исполнитель не идёт два раза подряд.
 */
object Recommender {

    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR

    // ---------- вкус ----------

    class Taste(
        /** Ключ исполнителя (Artists.norm) → вес, по убыванию. */
        val weights: List<Pair<String, Double>>,
        /** Ключ → трек этого исполнителя (чтобы найти его страницу). */
        val sample: Map<String, Track>,
        val names: Map<String, String>,
    ) {
        val keys: Set<String> = weights.map { it.first }.toSet()
    }

    fun taste(db: MusicDb): Taste {
        val now = System.currentTimeMillis()
        val w = HashMap<String, Double>()
        val sample = HashMap<String, Track>()
        val names = HashMap<String, String>()
        fun add(t: Track, v: Double) {
            val name = Artists.primary(t)
            val k = Artists.norm(name)
            if (k.length < 2) return
            w[k] = (w[k] ?: 0.0) + v
            sample.putIfAbsent(k, t)
            names.putIfAbsent(k, name)
        }
        db.playLog(600).forEach { (t, at, skipped) ->
            val decay = 0.5.pow((now - at).coerceAtLeast(0) / (21.0 * DAY)) // полураспад — 3 недели
            add(t, (if (skipped) -0.6 else 1.0) * decay)
        }
        db.liked().forEach { add(it, 2.0) }
        return Taste(
            weights = w.entries.filter { it.value > 0.2 }.sortedByDescending { it.value }.map { it.key to it.value },
            sample = sample,
            names = names,
        )
    }

    /** Взвешенный случайный выбор [n] элементов без повторов: тяжёлые — чаще, но не всегда. */
    fun <T> pick(items: List<Pair<T, Double>>, n: Int): List<T> =
        items.filter { it.second > 0 }
            .map { (x, wt) -> x to Math.random().pow(1.0 / wt) }
            .sortedByDescending { it.second }
            .take(n).map { it.first }

    private suspend fun refOf(taste: Taste, key: String): ArtistRef? =
        taste.sample[key]?.let { Artists.of(it) } ?: taste.names[key]?.let { Artists.lookup(it) }

    /**
     * Последние поиски, которые оказались именами исполнителей. У настоящего исполнителя
     * есть «похожие»; у каналов-сборников вроде «грустные песни» — нет, такие не берём.
     */
    private suspend fun searchedArtists(db: MusicDb, n: Int): List<ArtistRef> {
        val qs = withContext(Dispatchers.IO) { db.recentSearches(n) }
        return coroutineScope {
            qs.map { q ->
                async {
                    Artists.lookup(q)?.takeIf { ref -> Artists.page(ref)?.similar?.isNotEmpty() == true }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /** Похожие исполнители по нескольким страницам: кто упоминается чаще и выше — тот ближе. */
    private fun similarScores(pages: List<ArtistPage>, exclude: Set<String>, taste: Taste): List<Pair<ArtistRef, Double>> {
        val score = LinkedHashMap<String, Pair<ArtistRef, Double>>()
        pages.forEach { p ->
            p.similar.take(12).forEachIndexed { i, r ->
                if (r.id in exclude) return@forEachIndexed
                // уже знакомых чуть меньше — цель показать новое
                val known = Artists.norm(r.name) in taste.keys
                val v = (1.0 + (12 - i) / 12.0) * (if (known) 0.6 else 1.0)
                score[r.id] = r to ((score[r.id]?.second ?: 0.0) + v)
            }
        }
        return score.values.sortedByDescending { it.second }
    }

    /** Несколько песен исполнителя: сначала не слышанные, с долей случайности. */
    private fun songsOf(p: ArtistPage, n: Int, ok: (Track) -> Boolean, heard: Set<String>): List<Track> {
        val good = p.songs.filter(ok).take(12)
        val fresh = good.filter { it.id !in heard }.shuffled()
        val old = good.filter { it.id in heard }.shuffled()
        return (fresh + old).take(n)
    }

    /** Склеиваем списки по очереди и следим, чтобы исполнитель не шёл два раза подряд и не больше [perArtist]. */
    fun interleave(lists: List<List<Track>>, perArtist: Int, limit: Int): List<Track> {
        val raw = buildList {
            val max = lists.maxOfOrNull { it.size } ?: 0
            for (i in 0 until max) lists.forEach { l -> l.getOrNull(i)?.let(::add) }
        }.distinctBy { it.id }
        val count = HashMap<String, Int>()
        val pool = raw.filter { t ->
            val k = Artists.norm(Artists.primary(t))
            val n = count.getOrDefault(k, 0)
            count[k] = n + 1
            n < perArtist
        }.toMutableList()
        val out = ArrayList<Track>()
        while (pool.isNotEmpty() && out.size < limit) {
            val last = out.lastOrNull()?.let { Artists.norm(Artists.primary(it)) }
            val i = pool.indexOfFirst { Artists.norm(Artists.primary(it)) != last }.takeIf { it >= 0 } ?: 0
            out += pool.removeAt(i)
        }
        return out
    }

    // ---------- «Для вас» ----------

    suspend fun forYou(db: MusicDb): List<Track> = withContext(Dispatchers.IO) {
        val taste = taste(db)
        val skips = db.skipCounts()
        val disliked = db.disliked()
        val heard = db.heardIds()
        val justPlayed = db.playedSince(6 * HOUR)
        fun ok(t: Track) = t.id !in disliked && (skips[t.id] ?: 0) < 2 && t.id !in justPlayed

        val result = runCatching {
            coroutineScope {
                val searched = async { searchedArtists(db, 2) }
                val fromTaste = pick(taste.weights.take(20), 5).map { k -> async { refOf(taste, k) } }
                val seeds = (searched.await() + fromTaste.awaitAll().filterNotNull()).distinctBy { it.id }.take(6)
                if (seeds.isEmpty()) return@coroutineScope emptyList<Track>()

                // радио по недавнему хорошему треку — немного «того же настроения»
                val radio = async {
                    val seed = db.recent(10).filter { (skips[it.id] ?: 0) <= 0 }.randomOrNull()
                    seed?.let { s -> runCatching { Yt.radio(s.id) }.getOrDefault(emptyList()).filter(::ok).take(4) }
                        .orEmpty()
                }
                val seedPages = Artists.pages(seeds)
                val similar = similarScores(seedPages, seeds.map { it.id }.toSet(), taste)
                val chosen = pick(similar.take(24), 10)
                val simPages = Artists.pages(chosen)

                val lists = simPages.map { songsOf(it, 3, ::ok, heard) } +
                    seedPages.map { songsOf(it, 2, ::ok, heard) } +
                    listOf(radio.await())
                interleave(lists.shuffled(), perArtist = 3, limit = 40)
            }
        }.getOrDefault(emptyList())

        if (result.size >= 10) return@withContext result
        // похожих исполнителей найти не удалось (нет истории или YouTube Music не ответил) — по-старому
        (result + legacyForYou(db, taste)).distinctBy { it.id }.take(40)
    }

    /** Прежний способ: радио YouTube по любимым трекам. Без подмешивания поисковых запросов. */
    private suspend fun legacyForYou(db: MusicDb, taste: Taste): List<Track> = withContext(Dispatchers.IO) {
        val recent = db.recent(20)
        val skips = db.skipCounts()
        val disliked = db.disliked()
        val liked = db.liked()

        if (recent.isEmpty() && liked.isEmpty()) {
            // истории нет: исполнитель из последнего поиска, иначе просто популярное
            val artist = searchedArtists(db, 3).firstOrNull()
            return@withContext artist?.let { Artists.page(it)?.songs }?.takeIf { it.isNotEmpty() }
                ?: Yt.searchSongs(artist?.name ?: "популярные песни")
        }
        val good = recent.filter { (skips[it.id] ?: 0) <= 0 }
        val seeds = buildList {
            addAll(good.take(3))
            addAll(liked.shuffled().take(2))
            addAll(db.favoritesByPlays(10).shuffled().take(1))
            pick(taste.weights.take(10), 2).forEach { k -> taste.sample[k]?.let { add(it) } }
        }.distinctBy { it.id }.take(7)

        val radios = Continuation.radios(seeds.map { it.id })
        if (radios.all { it.isEmpty() }) return@withContext Yt.searchSongs("популярные песни")
        val justPlayed = db.playedSince(6 * HOUR)
        val seedIds = seeds.map { it.id }.toSet()
        Continuation.rank(
            radios, emptyMap(),
            exclude = { it.id in disliked || it.id in seedIds || it.id in justPlayed || (skips[it.id] ?: 0) >= 2 },
            penalty = { if ((skips[it.id] ?: 0) > 0) 1.0 else 0.0 },
            perArtist = 3, limit = 40, noise = 0.5,
        )
    }

    // ---------- продолжение ----------

    /**
     * Песни исполнителей, похожих на исполнителя трека [trackId]: включили Гуфа — дальше
     * такие же. [extra] — дополнительные исполнители-«семена» (например, из вашего вкуса).
     */
    suspend fun similarToTrack(
        db: MusicDb,
        trackId: String,
        exclude: (Track) -> Boolean,
        artists: Int = 5,
        perArtist: Int = 2,
        extra: List<ArtistRef> = emptyList(),
    ): List<Track> {
        val (cur, taste, heard) = withContext(Dispatchers.IO) { Triple(db.track(trackId), taste(db), db.heardIds()) }
        val curRef = cur?.let { Artists.of(it) }
        val seeds = (listOfNotNull(curRef) + extra).distinctBy { it.id }
        if (seeds.isEmpty()) return emptyList()
        val seedPages = Artists.pages(seeds)
        // похожие именно на текущего исполнителя — в приоритете
        val main = seedPages.firstOrNull { it.ref.id == curRef?.id }
        val similar = similarScores(listOfNotNull(main) + seedPages, seeds.map { it.id }.toSet(), taste)
        val chosen = pick(similar.take(15), artists)
        val simPages = Artists.pages(chosen)
        val ok = { t: Track -> !exclude(t) && t.id != trackId }
        val lists = simPages.map { songsOf(it, perArtist, ok, heard) } +
            seedPages.map { songsOf(it, 1, ok, heard) }
        return interleave(lists.shuffled(), perArtist = perArtist, limit = artists * perArtist + seeds.size)
    }

    /**
     * Следующая порция «Для вас», когда очередь подходит к концу: похожие на то,
     * что играет сейчас, плюс один исполнитель из вашего вкуса, плюс немного радио.
     */
    suspend fun more(db: MusicDb, current: String, queued: Set<String>): List<Track> {
        val (taste, ctx) = withContext(Dispatchers.IO) {
            taste(db) to Triple(db.skipCounts(), db.disliked(), db.playedSince(3 * HOUR))
        }
        val (skips, disliked, recently) = ctx
        val exclude = { t: Track ->
            t.id in queued || t.id in disliked || t.id in recently || (skips[t.id] ?: 0) >= 2
        }
        val extra = pick(taste.weights.take(15), 1).mapNotNull { refOf(taste, it) }
        val (similar, radio) = coroutineScope {
            val s = async { runCatching { similarToTrack(db, current, exclude, 5, 2, extra) }.getOrDefault(emptyList()) }
            val r = async {
                runCatching { Yt.radio(current) }.getOrDefault(emptyList()).filterNot(exclude).take(4)
            }
            s.await() to r.await()
        }
        val mixed = interleave(listOf(similar, radio), perArtist = 2, limit = 15)
        if (mixed.size >= 5) return mixed
        // запасной путь — прежнее радио по нескольким трекам
        val seeds = withContext(Dispatchers.IO) {
            buildList {
                add(current)
                addAll(db.recent(15).filter { (skips[it.id] ?: 0) <= 0 && it.id != current }.take(2).map { it.id })
                db.liked().shuffled().firstOrNull()?.let { add(it.id) }
            }.distinct()
        }
        val radios = Continuation.radios(seeds)
        val seedSet = seeds.toSet()
        val old = Continuation.rank(
            radios, emptyMap(),
            exclude = { exclude(it) || it.id in seedSet },
            penalty = { if ((skips[it.id] ?: 0) > 0) 1.0 else 0.0 },
            perArtist = 2, limit = 15, noise = 0.6,
        )
        return (mixed + old).distinctBy { it.id }.take(15)
    }

    /** Фильтр для автодобавления в радио во время прослушивания. */
    fun filterForQueue(db: MusicDb, list: List<Track>, inQueue: Set<String>): List<Track> {
        val disliked = db.disliked()
        val skips = db.skipCounts()
        val recently = db.playedSince(3 * HOUR)
        return list.filter {
            it.id !in inQueue && it.id !in disliked && it.id !in recently && (skips[it.id] ?: 0) < 2
        }
    }
}
