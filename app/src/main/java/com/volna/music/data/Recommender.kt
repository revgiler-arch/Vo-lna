package com.volna.music.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Лента «Для вас», собранная только по локальной истории.
 *
 * Берём несколько «семян» (последние дослушанные, любимые, частые исполнители,
 * последний поиск), для каждого получаем радио YouTube Music и ранжируем:
 * трек, всплывший в нескольких радио, и трек любимого исполнителя поднимаются выше;
 * пропущенные, дизлайкнутые и только что игравшие — опускаются или выкидываются.
 */
object Recommender {

    private const val HOUR = 3_600_000L

    suspend fun forYou(db: MusicDb): List<Track> = withContext(Dispatchers.IO) {
        val recent = db.recent(20)
        val skips = db.skipCounts()
        val disliked = db.disliked()
        val liked = db.liked()
        val artistW = db.artistWeights(15).toMap()
        val searches = db.recentSearches(3)

        if (recent.isEmpty() && liked.isEmpty()) {
            val q = searches.firstOrNull() ?: "популярные песни"
            return@withContext Yt.searchSongs(q) // ошибка уйдёт наверх и покажется пользователю
        }

        val good = recent.filter { (skips[it.id] ?: 0) <= 0 }
        val seeds = buildList {
            addAll(good.take(3))
            addAll(liked.shuffled().take(2))
            addAll(db.favoritesByPlays(10).shuffled().take(1))
            // по треку от каждого из 2 любимых исполнителей
            artistW.keys.take(2).forEach { a -> recent.firstOrNull { it.artist == a }?.let { add(it) } }
        }.distinctBy { it.id }.take(7)

        val radios = coroutineScope {
            val r = seeds.map { s -> async { runCatching { Yt.radio(s.id) }.getOrDefault(emptyList()) } }
            val s = searches.firstOrNull()?.let { q ->
                async { runCatching { Yt.searchSongs(q).take(8) }.getOrDefault(emptyList()) }
            }
            r.awaitAll() + listOfNotNull(s?.await())
        }
        // если не загрузилось вообще ничего — пробуем популярное, ошибку показываем как есть
        if (radios.all { it.isEmpty() }) {
            return@withContext Yt.searchSongs(searches.firstOrNull() ?: "популярные песни")
        }

        val justPlayed = db.playedSince(6 * HOUR)
        val seedIds = seeds.map { it.id }.toSet()
        val maxArtist = artistW.values.maxOrNull() ?: 1.0
        val score = HashMap<String, Double>()
        val byId = HashMap<String, Track>()

        radios.forEach { list ->
            list.take(25).forEachIndexed { i, t ->
                byId.putIfAbsent(t.id, t)
                score[t.id] = (score[t.id] ?: 0.0) + 1.0 + (25 - i) / 25.0
            }
        }

        val result = byId.values
            .filter { it.id !in disliked && it.id !in seedIds && (skips[it.id] ?: 0) < 2 }
            .map { t ->
                var s = score[t.id] ?: 0.0
                s += 1.5 * ((artistW[t.artist] ?: 0.0) / maxArtist)
                if (t.id in justPlayed) s -= 2.0
                if ((skips[t.id] ?: 0) > 0) s -= 1.0
                t to s
            }
            .sortedByDescending { it.second }
            .map { it.first }

        // не больше 3 треков одного исполнителя подряд в ленте
        val perArtist = HashMap<String, Int>()
        result.filter { t ->
            val n = perArtist.getOrDefault(t.artist, 0)
            perArtist[t.artist] = n + 1
            n < 3
        }.take(40)
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
