package com.volna.music.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** Чем занят слушатель — первая графа радио. */
enum class RadioActivity(val title: String, val ru: String, val en: String) {
    WORK("Работаю", "музыка для работы", "focus work music"),
    SLEEP("Перед сном", "музыка перед сном", "sleep music"),
    DRIVE("За рулём", "музыка в машину", "driving music"),
    SPORT("Тренируюсь", "музыка для тренировки", "workout music"),
}

/** Настроение — вторая графа радио. */
enum class RadioMood(val title: String, val ru: String, val en: String) {
    CALM("Спокойное", "спокойная", "chill"),
    SAD("Грустное", "грустная", "sad"),
    HAPPY("Весёлое", "весёлая", "happy"),
    ENERGY("Бодрое", "бодрая", "energetic"),
}

object MoodRadio {

    fun title(a: RadioActivity?, m: RadioMood?): String =
        listOfNotNull(m?.title, a?.title?.lowercase()).joinToString(", ").replaceFirstChar { it.uppercase() }
            .ifEmpty { "Радио" }

    private fun queries(a: RadioActivity?, m: RadioMood?): List<String> = when {
        a != null && m != null -> listOf("${m.ru} ${a.ru}", "${m.en} ${a.en}")
        a != null -> listOf(a.ru, a.en)
        m != null -> listOf("${m.ru} музыка", "${m.en} music")
        else -> listOf("популярные песни")
    }

    /**
     * Очередь радио: подборки YouTube Music под занятие и настроение,
     * подстроенные под вкус — любимые исполнители выше, «не рекомендовать»
     * и часто пропускаемое убираем, свежее (не слышанное недавно) вперёд.
     */
    suspend fun tracks(db: MusicDb, a: RadioActivity?, m: RadioMood?): List<Track> {
        val found = Yt.moodTracks(queries(a, m))
        return withContext(Dispatchers.IO) {
            val disliked = db.disliked()
            val skips = db.skipCounts()
            val recently = db.playedSince(6 * 3_600_000L)
            val artistW = db.artistWeights(30).toMap()
            val maxW = artistW.values.maxOrNull() ?: 1.0
            val ranked = found
                .filter { it.id !in disliked && (skips[it.id] ?: 0) < 2 }
                .map { t ->
                    var s = Math.random() // радио не должно каждый раз звучать одинаково
                    s += 1.2 * ((artistW[t.artist] ?: 0.0) / maxW)
                    if (t.id in recently) s -= 1.0
                    t to s
                }
                .sortedByDescending { it.second }
                .map { it.first }
            // не больше двух песен одного исполнителя в очереди
            val perArtist = HashMap<String, Int>()
            ranked.filter { t ->
                val n = perArtist.getOrDefault(t.artist, 0)
                perArtist[t.artist] = n + 1
                n < 2
            }.take(50)
        }
    }
}

/**
 * «Рекомендуем»: то, что вы ещё не слушали, — новые песни любимых исполнителей
 * и песни похожих на них исполнителей (их больше: цель — открыть новое).
 */
object Picks {
    /** Песни исполнителя: со страницы YouTube Music, иначе — поиском по имени. */
    private suspend fun songsOf(name: String, sample: Track?): List<Track> {
        val ref = sample?.let { Artists.of(it) } ?: Artists.lookup(name)
        ref?.let { Artists.page(it) }?.songs?.takeIf { it.isNotEmpty() }?.let { return it }
        return runCatching { Yt.searchSongs(name) }.getOrDefault(emptyList())
            .filter { Artists.sameName(Artists.primary(it), name) }
    }

    private suspend fun build(db: MusicDb, exclude: Set<String>, favorites: Int, similarN: Int, limit: Int): List<Track> {
        val (taste, heard, disliked) = withContext(Dispatchers.IO) {
            Triple(Recommender.taste(db), db.heardIds(), db.disliked())
        }
        fun fresh(t: Track) = t.id !in heard && t.id !in disliked && t.id !in exclude
        val keys = Recommender.pick(taste.weights.take(20), favorites)
        if (keys.isEmpty()) return emptyList()
        return coroutineScope {
            val favLists = keys.map { k ->
                async {
                    songsOf(taste.names[k] ?: return@async emptyList<Track>(), taste.sample[k])
                        .filter(::fresh).take(8).shuffled().take(3)
                }
            }
            // похожие на любимых — только незнакомые исполнители
            val simLists = async {
                val refs = keys.mapNotNull { k ->
                    taste.sample[k]?.let { Artists.of(it) } ?: taste.names[k]?.let { Artists.lookup(it) }
                }
                val pages = Artists.pages(refs)
                val seen = refs.map { it.id }.toSet()
                val candidates = pages.flatMap { p -> p.similar.take(10) }
                    .filter { it.id !in seen && Artists.norm(it.name) !in taste.keys }
                    .groupBy { it.id }.map { (_, l) -> l.first() to l.size.toDouble() }
                Artists.pages(Recommender.pick(candidates, similarN))
                    .map { p -> p.songs.filter(::fresh).take(8).shuffled().take(2) }
            }
            val lists = favLists.awaitAll() + simLists.await()
            Recommender.interleave(lists.shuffled(), perArtist = 3, limit = limit)
        }
    }

    suspend fun forYou(db: MusicDb, fallback: List<Track>): List<Track> {
        val list = runCatching { build(db, emptySet(), favorites = 4, similarN = 6, limit = 20) }
            .getOrDefault(emptyList())
        if (list.size >= 6) return list
        val heard = withContext(Dispatchers.IO) { db.heardIds() }
        return (list + fallback.filter { it.id !in heard }).distinctBy { it.id }.take(20)
            .ifEmpty { fallback.take(15) }
    }

    /** Следующая порция «Рекомендуем»: каждый раз другие исполнители — только ещё не слышанное. */
    suspend fun more(db: MusicDb, current: String, queued: Set<String>): List<Track> {
        val list = runCatching { build(db, queued, favorites = 3, similarN = 5, limit = 15) }
            .getOrDefault(emptyList())
        return if (list.size >= 4) list
        else (list + Recommender.more(db, current, queued + list.map { it.id }.toSet())).distinctBy { it.id }.take(15)
    }
}
