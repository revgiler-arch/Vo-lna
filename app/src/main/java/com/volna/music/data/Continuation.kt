package com.volna.music.data

import android.content.Context
import com.volna.music.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** Откуда запущена очередь — от этого зависит, чем её продолжать. */
enum class QueueMode {
    /** Обычное: плейлист, поиск, альбом — дальше радио по текущему треку. */
    RADIO,
    /** «Для вас»: дальше снова подборка по вкусу, с учётом того, что слушаете сейчас. */
    FOR_YOU,
    /** «Рекомендуем»: дальше новое (ещё не слышанное) у любимых и похожих исполнителей. */
    PICKS,
    /** Радио по занятию и настроению: дальше та же волна. */
    MOOD,
}

/** Текущий источник очереди. Хранится в настройках, чтобы пережить перезапуск сервиса. */
object QueueSource {
    private val prefs by lazy { App.instance.getSharedPreferences("queue_source", Context.MODE_PRIVATE) }

    @Volatile var mode: QueueMode = QueueMode.RADIO; private set
    @Volatile var activity: RadioActivity? = null; private set
    @Volatile var mood: RadioMood? = null; private set

    init {
        mode = runCatching { QueueMode.valueOf(prefs.getString("mode", null) ?: "RADIO") }
            .getOrDefault(QueueMode.RADIO)
        activity = runCatching { RadioActivity.valueOf(prefs.getString("activity", "") ?: "") }.getOrNull()
        mood = runCatching { RadioMood.valueOf(prefs.getString("mood", "") ?: "") }.getOrNull()
    }

    fun set(m: QueueMode, a: RadioActivity? = null, md: RadioMood? = null) {
        mode = m; activity = a; mood = md
        prefs.edit()
            .putString("mode", m.name)
            .putString("activity", a?.name)
            .putString("mood", md?.name)
            .apply()
    }
}

/**
 * Продолжение очереди «как радио»: когда подходит к концу — догружаем ещё порцию
 * в духе того, откуда очередь запущена. Если основной путь ничего не дал —
 * пробуем запасные, чтобы музыка не останавливалась.
 */
object Continuation {

    private const val HOUR = 3_600_000L

    suspend fun more(db: MusicDb, seed: String, queued: Set<String>, attempt: Int = 0): List<Track> {
        val primary = runCatching {
            when (QueueSource.mode) {
                QueueMode.FOR_YOU -> Recommender.more(db, seed, queued)
                QueueMode.PICKS -> Picks.more(db, seed, queued)
                QueueMode.MOOD -> moodMore(db, queued)
                QueueMode.RADIO -> radioMore(db, seed, queued)
            }
        }.getOrDefault(emptyList())
        if (primary.isNotEmpty()) return primary
        return fallback(db, seed, queued, attempt)
    }

    private suspend fun radioMore(db: MusicDb, seed: String, queued: Set<String>): List<Track> {
        val radio = Yt.radio(seed)
        return withContext(Dispatchers.IO) { Recommender.filterForQueue(db, radio, queued).take(15) }
    }

    private suspend fun moodMore(db: MusicDb, queued: Set<String>): List<Track> {
        val list = MoodRadio.tracks(db, QueueSource.activity, QueueSource.mood)
        val recently = withContext(Dispatchers.IO) { db.playedSince(3 * HOUR) }
        return list.filter { it.id !in queued && it.id !in recently }.take(20)
    }

    /** Запасные пути: радио по другому треку, затем поиск по любимому исполнителю. */
    private suspend fun fallback(db: MusicDb, seed: String, queued: Set<String>, attempt: Int): List<Track> =
        withContext(Dispatchers.IO) {
            val disliked = db.disliked()
            fun ok(l: List<Track>) = l.filter { it.id !in queued && it.id !in disliked }

            // 1) радио по текущему или (на повторных попытках) по случайному любимому треку
            val altSeed = if (attempt == 0) seed else
                (db.liked() + db.favoritesByPlays(20)).map { it.id }.shuffled().firstOrNull() ?: seed
            ok(runCatching { Yt.radio(altSeed) }.getOrDefault(emptyList())).take(15)
                .ifEmpty {
                    // 2) песни одного из любимых исполнителей
                    val artist = db.artistWeights(10).map { it.first }.shuffled().firstOrNull()
                    ok(runCatching { Yt.searchSongs(artist ?: "популярные песни") }.getOrDefault(emptyList()))
                        .shuffled().take(10)
                }
        }

    /** Общий подсчёт: трек, всплывший в нескольких радио, и любимый исполнитель — выше. */
    internal fun rank(
        radios: List<List<Track>>,
        artistW: Map<String, Double>,
        exclude: (Track) -> Boolean,
        penalty: (Track) -> Double,
        perArtist: Int,
        limit: Int,
        noise: Double = 0.0,
    ): List<Track> {
        val maxArtist = artistW.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val score = HashMap<String, Double>()
        val byId = LinkedHashMap<String, Track>()
        radios.forEach { list ->
            list.take(25).forEachIndexed { i, t ->
                byId.putIfAbsent(t.id, t)
                score[t.id] = (score[t.id] ?: 0.0) + 1.0 + (25 - i) / 25.0
            }
        }
        val ranked = byId.values
            .filterNot(exclude)
            .map { t ->
                var s = score[t.id] ?: 0.0
                s += 1.5 * ((artistW[t.artist] ?: 0.0) / maxArtist)
                s -= penalty(t)
                if (noise > 0) s += Math.random() * noise
                t to s
            }
            .sortedByDescending { it.second }
            .map { it.first }
        val count = HashMap<String, Int>()
        return ranked.filter { t ->
            val n = count.getOrDefault(t.artist, 0)
            count[t.artist] = n + 1
            n < perArtist
        }.take(limit)
    }

    /** Параллельно получить радио по нескольким трекам. */
    internal suspend fun radios(seeds: List<String>): List<List<Track>> = coroutineScope {
        seeds.map { s -> async { runCatching { Yt.radio(s) }.getOrDefault(emptyList()) } }.awaitAll()
    }
}
