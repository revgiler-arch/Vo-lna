package com.volna.music.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Локальная база: история прослушиваний, поиски, оценки (лайк/дизлайк).
 * Всё хранится только на телефоне.
 */
class MusicDb(context: Context) : SQLiteOpenHelper(context, "volna.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE tracks(
                vid TEXT PRIMARY KEY, title TEXT, artist TEXT, artist_url TEXT,
                thumb TEXT, duration INTEGER)"""
        )
        db.execSQL(
            """CREATE TABLE plays(
                id INTEGER PRIMARY KEY AUTOINCREMENT, vid TEXT, at INTEGER,
                listened_ms INTEGER DEFAULT 0, skipped INTEGER DEFAULT 0)"""
        )
        db.execSQL("CREATE INDEX plays_vid ON plays(vid)")
        db.execSQL("CREATE INDEX plays_at ON plays(at)")
        db.execSQL("CREATE TABLE ratings(vid TEXT PRIMARY KEY, r INTEGER, at INTEGER)")
        db.execSQL("CREATE TABLE searches(q TEXT PRIMARY KEY, at INTEGER, n INTEGER)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ---------- треки ----------

    fun saveTrack(t: Track) {
        writableDatabase.insertWithOnConflict("tracks", null, ContentValues().apply {
            put("vid", t.id); put("title", t.title); put("artist", t.artist)
            put("artist_url", t.artistUrl); put("thumb", t.thumb); put("duration", t.durationSec)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun Cursor.track() = Track(
        id = getString(0), title = getString(1) ?: "", artist = getString(2) ?: "",
        artistUrl = getString(3), thumb = getString(4), durationSec = getLong(5),
    )

    private fun tracks(sql: String, vararg args: String): List<Track> =
        readableDatabase.rawQuery(sql, args).use { c ->
            buildList { while (c.moveToNext()) add(c.track()) }
        }

    // ---------- прослушивания ----------

    fun addPlay(vid: String): Long =
        writableDatabase.insert("plays", null, ContentValues().apply {
            put("vid", vid); put("at", System.currentTimeMillis())
        })

    fun finishPlay(rowId: Long, listenedMs: Long, skipped: Boolean) {
        writableDatabase.update("plays", ContentValues().apply {
            put("listened_ms", listenedMs); put("skipped", if (skipped) 1 else 0)
        }, "id=?", arrayOf(rowId.toString()))
    }

    /** Последние прослушанные (без повторов), новые сверху. */
    fun recent(limit: Int = 50): List<Track> = tracks(
        """SELECT t.vid,t.title,t.artist,t.artist_url,t.thumb,t.duration
           FROM tracks t JOIN (SELECT vid, MAX(at) m FROM plays GROUP BY vid) p ON p.vid=t.vid
           ORDER BY p.m DESC LIMIT $limit"""
    )

    /** Треки, которые дослушивали чаще всего. */
    fun favoritesByPlays(limit: Int = 30): List<Track> = tracks(
        """SELECT t.vid,t.title,t.artist,t.artist_url,t.thumb,t.duration
           FROM tracks t JOIN (SELECT vid, COUNT(*) n FROM plays WHERE skipped=0 GROUP BY vid) p
           ON p.vid=t.vid ORDER BY p.n DESC LIMIT $limit"""
    )

    /** Исполнители с весом: сколько раз их дослушивали (лайк = +3). */
    fun artistWeights(limit: Int = 20): List<Pair<String, Double>> =
        readableDatabase.rawQuery(
            """SELECT t.artist,
                 SUM(CASE WHEN p.skipped=0 THEN 1.0 ELSE -0.5 END)
                 + 3.0 * (SELECT COUNT(*) FROM ratings r JOIN tracks t2 ON t2.vid=r.vid
                          WHERE r.r>0 AND t2.artist=t.artist) AS w
               FROM plays p JOIN tracks t ON t.vid=p.vid
               WHERE t.artist<>'' GROUP BY t.artist HAVING w>0 ORDER BY w DESC LIMIT $limit""",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getDouble(1)) } }

    /** Сколько раз трек пропускали в первые секунды. */
    fun skipCounts(): Map<String, Int> =
        readableDatabase.rawQuery(
            "SELECT vid, SUM(skipped), COUNT(*) FROM plays GROUP BY vid", null,
        ).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1) * 2 - c.getInt(2)) }
        }

    /** id треков, игравших за последние [ms] миллисекунд. */
    fun playedSince(ms: Long): Set<String> =
        readableDatabase.rawQuery(
            "SELECT DISTINCT vid FROM plays WHERE at>?",
            arrayOf((System.currentTimeMillis() - ms).toString()),
        ).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

    /** id всех треков, которые когда-либо играли. */
    fun heardIds(): Set<String> =
        readableDatabase.rawQuery("SELECT DISTINCT vid FROM plays", null)
            .use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

    fun clearHistory() {
        writableDatabase.delete("plays", null, null)
        writableDatabase.delete("searches", null, null)
    }

    // ---------- оценки ----------

    fun rating(vid: String): Int =
        readableDatabase.rawQuery("SELECT r FROM ratings WHERE vid=?", arrayOf(vid))
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** 1 = нравится, -1 = не рекомендовать, 0 = убрать оценку. */
    fun setRating(t: Track, r: Int) {
        saveTrack(t)
        if (r == 0) {
            writableDatabase.delete("ratings", "vid=?", arrayOf(t.id))
        } else {
            writableDatabase.insertWithOnConflict("ratings", null, ContentValues().apply {
                put("vid", t.id); put("r", r); put("at", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun liked(): List<Track> = tracks(
        """SELECT t.vid,t.title,t.artist,t.artist_url,t.thumb,t.duration
           FROM tracks t JOIN ratings r ON r.vid=t.vid WHERE r.r>0 ORDER BY r.at DESC"""
    )

    fun disliked(): Set<String> =
        readableDatabase.rawQuery("SELECT vid FROM ratings WHERE r<0", null)
            .use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

    // ---------- поиски ----------

    fun addSearch(q: String) {
        val query = q.trim()
        if (query.isEmpty()) return
        // UPSERT появился только в Android 11, поэтому по-старому
        val db = writableDatabase
        db.execSQL(
            "UPDATE searches SET at=?, n=n+1 WHERE q=?",
            arrayOf<Any>(System.currentTimeMillis(), query),
        )
        db.execSQL(
            "INSERT OR IGNORE INTO searches(q,at,n) VALUES(?,?,1)",
            arrayOf<Any>(query, System.currentTimeMillis()),
        )
    }

    fun recentSearches(limit: Int = 10): List<String> =
        readableDatabase.rawQuery("SELECT q FROM searches ORDER BY at DESC LIMIT $limit", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    fun deleteSearch(q: String) {
        writableDatabase.delete("searches", "q=?", arrayOf(q))
    }
}
