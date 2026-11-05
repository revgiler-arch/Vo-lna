package com.volna.music.data

import android.content.Context
import android.webkit.CookieManager
import com.volna.music.App
import com.volna.music.net.OkDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

class AuthExpiredException : IOException("Сессия Google истекла — войдите в аккаунт заново")

data class YtPlaylist(val browseId: String, val title: String, val subtitle: String, val thumb: String?)

/**
 * Вход в аккаунт YouTube Music: после входа через окно Google сохраняем cookies
 * (только в памяти приложения на телефоне) и ходим к music.youtube.com от вашего имени.
 */
object YtAccount {
    private const val ORIGIN = "https://music.youtube.com"
    private const val FALLBACK_VERSION = "1.20260804.16.00"

    private val prefs get() = App.instance.getSharedPreferences("yt_account", Context.MODE_PRIVATE)

    var cookie: String?
        get() = prefs.getString("cookie", null)
        set(v) { prefs.edit().putString("cookie", v).apply() }

    var accountName: String?
        get() = prefs.getString("name", null)
        set(v) { prefs.edit().putString("name", v).apply() }

    val isLoggedIn: Boolean get() = sapisid(cookie) != null

    fun logout() {
        prefs.edit().clear().apply()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val clientVersion by lazy {
        runCatching { YoutubeParsingHelper.getYoutubeMusicClientVersion() }.getOrNull() ?: FALLBACK_VERSION
    }

    private fun sapisid(c: String?): String? = c?.split(";")?.map { it.trim() }
        ?.firstOrNull { it.startsWith("SAPISID=") || it.startsWith("__Secure-3PAPISID=") }
        ?.substringAfter("=")

    private fun authHeader(c: String): String {
        val ts = System.currentTimeMillis() / 1000
        val raw = "$ts ${sapisid(c)} $ORIGIN"
        val hash = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${ts}_$hash"
    }

    private fun post(endpoint: String, body: JSONObject = JSONObject()): JSONObject {
        val c = cookie ?: throw AuthExpiredException()
        val lang = Locale.getDefault().language.takeIf { it.matches(Regex("[a-z]{2,3}")) } ?: "en"
        body.put(
            "context",
            JSONObject().put(
                "client",
                JSONObject().put("clientName", "WEB_REMIX").put("clientVersion", clientVersion).put("hl", lang),
            ),
        )
        val req = Request.Builder()
            .url("$ORIGIN/youtubei/v1/$endpoint?prettyPrint=false")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Cookie", c)
            .header("Authorization", authHeader(c))
            .header("X-Origin", ORIGIN)
            .header("Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
            .header("X-Goog-AuthUser", "0")
            .header("User-Agent", OkDownloader.USER_AGENT)
            .build()
        http.newCall(req).execute().use { r ->
            if (r.code == 401 || r.code == 403) throw AuthExpiredException()
            if (!r.isSuccessful) throw IOException("YouTube Music ответил HTTP ${r.code}")
            return JSONObject(r.body?.string() ?: "{}")
        }
    }

    // ---------- публичные запросы ----------

    suspend fun fetchAccountName(): String? = withContext(Dispatchers.IO) {
        val json = post("account/account_menu")
        var name: String? = null
        walk(json) { key, obj ->
            if (name == null && key == "accountName") name = text(obj)
        }
        name?.also { accountName = it }
    }

    /** «Понравившиеся» из YouTube Music. */
    suspend fun likedSongs(): List<Track> = withContext(Dispatchers.IO) { browseTracks("FEmusic_liked_videos") }

    suspend fun playlistTracks(browseId: String): List<Track> = withContext(Dispatchers.IO) { browseTracks(browseId) }

    /** Ваши плейлисты (созданные и сохранённые). */
    suspend fun playlists(): List<YtPlaylist> = withContext(Dispatchers.IO) {
        val out = mutableListOf<YtPlaylist>()
        walk(post("browse", JSONObject().put("browseId", "FEmusic_liked_playlists"))) { key, obj ->
            if (key != "musicTwoRowItemRenderer") return@walk
            val id = obj.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                ?.optString("browseId").orEmpty()
            if (!id.startsWith("VL")) return@walk
            out += YtPlaylist(
                browseId = id,
                title = text(obj.optJSONObject("title")),
                subtitle = text(obj.optJSONObject("subtitle")),
                thumb = thumb(obj),
            )
        }
        out.distinctBy { it.browseId }
    }

    /** Персональная главная YouTube Music: «Быстрый выбор», миксы и т.п. — только песни. */
    suspend fun home(): List<Track> = withContext(Dispatchers.IO) {
        val json = post("browse", JSONObject().put("browseId", "FEmusic_home"))
        val out = mutableListOf<Track>()
        walk(json) { key, obj ->
            when (key) {
                "musicResponsiveListItemRenderer" -> listItem(obj)?.let { out += it }
                "musicTwoRowItemRenderer" -> {
                    val vid = obj.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")
                        ?.optString("videoId").orEmpty()
                    if (vid.isNotEmpty()) {
                        out += Track(
                            id = vid,
                            title = text(obj.optJSONObject("title")),
                            artist = firstRun(obj.optJSONObject("subtitle")),
                            artistUrl = null,
                            thumb = thumb(obj),
                            durationSec = 0,
                        )
                    }
                }
            }
        }
        out.distinctBy { it.id }
    }

    /** Синхронизировать лайк с аккаунтом. */
    suspend fun setLike(videoId: String, liked: Boolean) = withContext(Dispatchers.IO) {
        post(if (liked) "like/like" else "like/removelike",
            JSONObject().put("target", JSONObject().put("videoId", videoId)))
        Unit
    }

    // ---------- разбор ответов ----------

    private fun browseTracks(browseId: String): List<Track> {
        val out = mutableListOf<Track>()
        var json = post("browse", JSONObject().put("browseId", browseId))
        repeat(20) { // до ~2000 треков
            var token: String? = null
            walk(json) { key, obj ->
                when (key) {
                    "musicResponsiveListItemRenderer" -> listItem(obj)?.let { out += it }
                    "continuationCommand" -> token = token ?: obj.optString("token").ifEmpty { null }
                    "nextContinuationData" -> token = token ?: obj.optString("continuation").ifEmpty { null }
                }
            }
            val t = token ?: return out.distinctBy { it.id }
            json = post("browse", JSONObject().put("continuation", t))
        }
        return out.distinctBy { it.id }
    }

    private fun listItem(r: JSONObject): Track? {
        val vid = r.optJSONObject("playlistItemData")?.optString("videoId")?.ifEmpty { null }
            ?: findFirst(r, "watchEndpoint")?.optString("videoId")?.ifEmpty { null }
            ?: return null
        val cols = r.optJSONArray("flexColumns") ?: return null
        fun col(i: Int) = cols.optJSONObject(i)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
        val title = text(col(0)).ifEmpty { return null }

        // исполнители — фрагменты со ссылкой; иначе первый кусок до « • »
        val runs = col(1)?.optJSONArray("runs")
        val linked = buildList {
            if (runs != null) for (i in 0 until runs.length()) {
                val run = runs.optJSONObject(i) ?: continue
                val page = run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                    ?.toString().orEmpty()
                if (page.contains("MUSIC_PAGE_TYPE_ARTIST") || page.contains("MUSIC_PAGE_TYPE_USER_CHANNEL")) {
                    add(run.optString("text"))
                }
            }
        }
        val artist = linked.joinToString(", ").ifEmpty { text(col(1)).substringBefore(" • ").trim() }

        val fixed = r.optJSONArray("fixedColumns")?.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")?.optJSONObject("text")
        val durText = text(fixed).ifEmpty {
            Regex("""\d{1,2}:\d{2}(:\d{2})?""").findAll(text(col(1))).lastOrNull()?.value.orEmpty()
        }
        return Track(vid, title, artist, null, thumb(r), parseDuration(durText))
    }

    private fun parseDuration(s: String): Long =
        s.split(":").mapNotNull { it.trim().toLongOrNull() }.fold(0L) { acc, n -> acc * 60 + n }

    private fun text(o: JSONObject?): String {
        if (o == null) return ""
        o.optString("simpleText").takeIf { it.isNotEmpty() }?.let { return it }
        val runs = o.optJSONArray("runs") ?: return ""
        return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
    }

    private fun firstRun(o: JSONObject?): String =
        o?.optJSONArray("runs")?.optJSONObject(0)?.optString("text").orEmpty()

    private fun thumb(o: JSONObject): String? {
        val arr = findFirst(o, "musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            ?: return null
        val url = arr.optJSONObject(arr.length() - 1)?.optString("url")?.ifEmpty { null } ?: return null
        // у обложек YouTube Music размер задаётся в адресе — просим побольше
        return url.replace(Regex("""=w\d+-h\d+"""), "=w544-h544")
    }

    /** Обойти весь JSON и вызвать [f] для каждого вложенного объекта с его ключом. */
    private fun walk(node: Any?, f: (String, JSONObject) -> Unit) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys().asSequence().toList()
                for (k in keys) {
                    val v = node.opt(k)
                    if (v is JSONObject) f(k, v)
                    walk(v, f)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), f)
        }
    }

    private fun findFirst(node: Any?, key: String): JSONObject? {
        when (node) {
            is JSONObject -> {
                node.optJSONObject(key)?.let { return it }
                for (k in node.keys().asSequence().toList()) findFirst(node.opt(k), key)?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) findFirst(node.opt(i), key)?.let { return it }
        }
        return null
    }
}
