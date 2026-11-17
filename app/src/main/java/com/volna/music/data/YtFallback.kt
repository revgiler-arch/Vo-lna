package com.volna.music.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Запасной путь к потокам, когда основной клиент (NewPipeExtractor, клиент visionOS)
 * получает от YouTube «подтвердите, что вы не бот».
 *
 * Спрашиваем YouTube сами от имени других официальных клиентов — у них своя проверка:
 *  1. ТВ-приложение (TVHTML5) с вашим аккаунтом, если вы вошли, — с аккаунтом проверки на бота нет;
 *  2. то же ТВ-приложение без аккаунта;
 *  3. приложение для VR-шлема (ANDROID_VR).
 * Каждую найденную ссылку проверяем коротким запросом, прежде чем отдать плееру.
 */
object YtFallback {
    private const val WWW = "https://www.youtube.com"
    private const val TTL = 4 * 3_600_000L
    private const val BLOCK_MEMORY = 30 * 60_000L

    class Streams(val client: String, val audio: String, val video: String?, val at: Long)

    private class Client(
        val key: String,
        val name: String,
        val nameId: Int,
        val version: String,
        val ua: String,
        val extra: Map<String, Any> = emptyMap(),
        val usesJsPlayer: Boolean = false,
    )

    // Версии и параметры — как у yt-dlp (2026.10), он следит за ними постоянно.
    private val TV = Client(
        key = "tv", name = "TVHTML5", nameId = 7, version = "5.20260707",
        ua = "Mozilla/5.0 (SMART-TV; Linux; Tizen 2.4.0) AppleWebKit/538.1 (KHTML, like Gecko) " +
            "Version/2.4.0 TV Safari/538.1",
        extra = mapOf("deviceMake" to "Samsung", "deviceModel" to "UKS9800", "osName" to "Tizen", "osVersion" to "2.4.0"),
        usesJsPlayer = true,
    )
    private val VR = Client(
        key = "vr", name = "ANDROID_VR", nameId = 28, version = "1.65.10",
        ua = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; " +
            "eureka-user Build/SQ3A.220605.009.A1) gzip",
        extra = mapOf(
            "deviceMake" to "Oculus", "deviceModel" to "Quest 3", "androidSdkVersion" to 32,
            "osName" to "Android", "osVersion" to "12L",
        ),
    )
    private val clients = listOf(TV, VR)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json".toMediaType()

    private val cache = ConcurrentHashMap<String, Streams>()
    private val visitors = ConcurrentHashMap<String, Pair<String, Long>>()

    @Volatile
    private var blockedUntil = 0L

    /** Что пробовали в последний раз и чем кончилось (для диагностики). */
    @Volatile
    var lastReport: String? = null
        private set

    /** Основной клиент недавно получил проверку на бота — сразу идём запасным путём. */
    val preferred: Boolean get() = System.currentTimeMillis() < blockedUntil

    fun markBlocked() {
        blockedUntil = System.currentTimeMillis() + BLOCK_MEMORY
    }

    fun forget(id: String) {
        cache.remove(id)
    }

    /** Ссылки googlevideo проверяют, что их качает «тот же» клиент, — подставляем его User-Agent. */
    fun userAgentFor(url: String): String? {
        val c = Regex("[?&]c=([A-Za-z0-9_]+)").find(url)?.groupValues?.get(1) ?: return null
        return clients.firstOrNull { it.name.equals(c, ignoreCase = true) }?.ua
    }

    /** Блокирующий вызов. */
    fun streams(id: String): Streams {
        cache[id]?.let { if (System.currentTimeMillis() - it.at < TTL) return it }
        val auth = runCatching { YtAccount.authHeaders(WWW) }.getOrNull()
        val attempts = buildList {
            if (auth != null) add(TV to auth)
            clients.forEach { add(it to null) }
        }
        val errors = mutableListOf<String>()
        for ((client, a) in attempts) {
            val label = client.key + if (a != null) "+аккаунт" else ""
            try {
                val s = fetch(id, client, a, label)
                if (cache.size > 100) cache.clear()
                cache[id] = s
                lastReport = "$id: $label OK" + if (errors.isEmpty()) "" else " (до этого: ${errors.joinToString("; ")})"
                return s
            } catch (e: Exception) {
                errors += "$label: ${e.message?.take(140) ?: e.javaClass.simpleName}"
            }
        }
        lastReport = "$id: ${errors.joinToString("; ")}"
        throw IOException("Запасные клиенты тоже не смогли: ${errors.joinToString("; ")}")
    }

    private fun fetch(id: String, c: Client, auth: Map<String, String>?, label: String): Streams {
        val client = clientJson(c)
        val visitor = if (auth == null) visitorData(c) else null
        visitor?.let { client.put("visitorData", it) }
        val body = JSONObject()
            .put("context", JSONObject().put("client", client))
            .put("videoId", id)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
        if (c.usesJsPlayer) {
            val pc = JSONObject().put("html5Preference", "HTML5_PREF_WANTS")
            runCatching { YoutubeJavaScriptPlayerManager.getSignatureTimestamp(id) }.getOrNull()
                ?.let { pc.put("signatureTimestamp", it) }
            body.put("playbackContext", JSONObject().put("contentPlaybackContext", pc))
        }

        val json = post("$WWW/youtubei/v1/player?prettyPrint=false", body, c, auth, visitor)
        val ps = json.optJSONObject("playabilityStatus")
        val status = ps?.optString("status").orEmpty()
        if (status != "OK") throw IOException("$status ${ps?.optString("reason").orEmpty()}".trim())
        val sd = json.optJSONObject("streamingData") ?: throw IOException("нет streamingData")

        val adaptive = sd.optJSONArray("adaptiveFormats").objects()
        val muxed = sd.optJSONArray("formats").objects()

        val audios = adaptive.filter {
            it.optString("mimeType").startsWith("audio/") && !it.optBoolean("isDrc")
        }
        val original = audios.filter {
            val t = it.optJSONObject("audioTrack") ?: return@filter true
            t.optBoolean("audioIsDefault", true)
        }.ifEmpty { audios }
        val audioFormat = original.maxByOrNull { it.optInt("averageBitrate", it.optInt("bitrate")) }
            ?: throw IOException("нет аудиопотока")
        val audio = urlOf(id, audioFormat)
        verify(audio, c.ua)

        val video = runCatching {
            val vf = adaptive.filter {
                it.optString("mimeType").startsWith("video/mp4") && it.optInt("height") in 1..480
            }.maxByOrNull { it.optInt("height") }
                ?: muxed.filter { it.optString("mimeType").startsWith("video/mp4") }
                    .minByOrNull { kotlin.math.abs(it.optInt("height") - 360) }
            vf?.let { urlOf(id, it) }
        }.getOrNull()

        return Streams(label, audio, video, System.currentTimeMillis())
    }

    private fun clientJson(c: Client): JSONObject {
        val loc = runCatching { NewPipe.getPreferredLocalization() }.getOrNull()
        val country = runCatching { NewPipe.getPreferredContentCountry().countryCode }.getOrNull()
        val o = JSONObject()
            .put("clientName", c.name)
            .put("clientVersion", c.version)
            .put("userAgent", c.ua)
            .put("hl", loc?.languageCode ?: "en")
            .put("gl", country ?: "US")
        c.extra.forEach { (k, v) -> o.put(k, v) }
        return o
    }

    /** visitorData — «гостевой» идентификатор; без него ответы YouTube бывают неполными. */
    private fun visitorData(c: Client): String? {
        visitors[c.key]?.let { (v, at) -> if (System.currentTimeMillis() - at < 3_600_000L) return v }
        val body = JSONObject().put("context", JSONObject().put("client", clientJson(c)))
        val v = runCatching {
            post("$WWW/youtubei/v1/visitor_id?prettyPrint=false", body, c, null, null)
                .optJSONObject("responseContext")?.optString("visitorData")
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        visitors[c.key] = v to System.currentTimeMillis()
        return v
    }

    private fun post(
        url: String,
        body: JSONObject,
        c: Client,
        auth: Map<String, String>?,
        visitor: String?,
    ): JSONObject {
        val b = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON))
            .header("User-Agent", c.ua)
            .header("X-YouTube-Client-Name", c.nameId.toString())
            .header("X-YouTube-Client-Version", c.version)
            .header("Origin", WWW)
        auth?.forEach { (k, v) -> b.header(k, v) }
        visitor?.let { b.header("X-Goog-Visitor-Id", it) }
        http.newCall(b.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return JSONObject(text)
        }
    }

    /** Ссылка формата: прямая или зашифрованная (ТВ-клиент) — расшифровываем кодом плеера YouTube. */
    private fun urlOf(id: String, f: JSONObject): String {
        var url = f.optString("url").takeIf { it.isNotEmpty() } ?: run {
            val cipher = f.optString("signatureCipher").ifEmpty { f.optString("cipher") }
            if (cipher.isEmpty()) throw IOException("нет ссылки на поток")
            val p = parseQuery(cipher)
            val base = p["url"] ?: throw IOException("нет url в шифре")
            val s = p["s"] ?: throw IOException("нет подписи в шифре")
            val sp = p["sp"] ?: "signature"
            val sig = YoutubeJavaScriptPlayerManager.deobfuscateSignature(id, s)
            "$base&$sp=${URLEncoder.encode(sig, "UTF-8")}"
        }
        // параметр n: без расшифровки YouTube режет скорость или отвечает 403
        url = runCatching { YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(id, url) }
            .getOrDefault(url)
        return url
    }

    /** Проверяем, что ссылка действительно отдаёт звук, а не 403. */
    private fun verify(url: String, ua: String) {
        val req = Request.Builder().url(url).header("User-Agent", ua).header("Range", "bytes=0-1").build()
        http.newCall(req).execute().use { r ->
            if (r.code !in 200..299) throw IOException("поток отвечает HTTP ${r.code}")
        }
    }

    private fun parseQuery(q: String): Map<String, String> = q.split("&").mapNotNull {
        val i = it.indexOf('=')
        if (i <= 0) null else it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), "UTF-8")
    }.toMap()

    private fun org.json.JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
