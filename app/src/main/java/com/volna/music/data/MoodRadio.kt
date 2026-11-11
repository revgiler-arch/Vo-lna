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

/** «Рекомендуем»: новые для вас песни любимых исполнителей — то, что вы ещё не слушали. */
object Picks {
    suspend fun forYou(db: MusicDb, fallback: List<Track>): List<Track> {
        val artists = withContext(Dispatchers.IO) { db.artistWeights(6).map { it.first } }
            .filter { it.isNotBlank() }.take(4)
        if (artists.isEmpty()) return fallback.take(15)
        val (heard, disliked) = withContext(Dispatchers.IO) { db.heardIds() to db.disliked() }
        val lists = coroutineScope {
            artists.map { a ->
                async {
                    runCatching { Yt.searchSongs(a) }.getOrDefault(emptyList())
                        .filter { it.artist.equals(a, ignoreCase = true) && it.id !in heard && it.id !in disliked }
                        .take(6)
                }
            }.awaitAll()
        }
        // по очереди от каждого исполнителя, чтобы ряд был разнообразным
        val mixed = buildList {
            val max = lists.maxOfOrNull { it.size } ?: 0
            for (i in 0 until max) lists.forEach { l -> l.getOrNull(i)?.let(::add) }
        }.distinctBy { it.id }
        return (mixed + fallback.filter { it.id !in heard }).distinctBy { it.id }.take(20)
            .ifEmpty { fallback.take(15) }
    }
}
