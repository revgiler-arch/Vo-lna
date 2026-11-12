package com.volna.music.ui

import android.app.Application
import android.content.ComponentName
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.volna.music.App
import com.volna.music.data.Recommender
import com.volna.music.data.MoodRadio
import com.volna.music.data.Picks
import com.volna.music.data.RadioActivity
import com.volna.music.data.RadioMood
import com.volna.music.player.VideoPreview
import com.volna.music.data.Track
import com.volna.music.data.LocalPlaylist
import com.volna.music.data.PlaylistImport
import com.volna.music.data.Yt
import com.volna.music.data.Updater
import com.volna.music.data.UpdateInfo
import com.volna.music.data.YtAccount
import com.volna.music.data.YtPlaylist
import com.volna.music.data.AuthExpiredException
import com.volna.music.player.MediaItems
import com.volna.music.player.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class MainVm(app: Application) : AndroidViewModel(app) {

    private val db = App.instance.db

    // ---------- плеер ----------
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    var nowPlaying by mutableStateOf<Track?>(null); private set
    var isPlaying by mutableStateOf(false); private set
    var isBuffering by mutableStateOf(false); private set
    var positionMs by mutableStateOf(0L); private set
    var durationMs by mutableStateOf(0L); private set
    var queue by mutableStateOf<List<Track>>(emptyList()); private set
    var queueIndex by mutableStateOf(0); private set
    var shuffle by mutableStateOf(false); private set
    var repeatMode by mutableStateOf(Player.REPEAT_MODE_OFF); private set
    var rating by mutableStateOf(0); private set

    // ---------- главная ----------
    var forYou by mutableStateOf<List<Track>>(emptyList()); private set
    var recent by mutableStateOf<List<Track>>(emptyList()); private set
    /** «Рекомендуем»: новое у любимых исполнителей. */
    var picks by mutableStateOf<List<Track>>(emptyList()); private set
    var topArtists by mutableStateOf<List<String>>(emptyList()); private set
    var homeLoading by mutableStateOf(false); private set
    var homeError by mutableStateOf<String?>(null); private set

    // ---------- поиск ----------
    var query by mutableStateOf(""); private set
    var results by mutableStateOf<List<Track>>(emptyList()); private set
    var suggestions by mutableStateOf<List<String>>(emptyList()); private set
    var recentSearches by mutableStateOf<List<String>>(emptyList()); private set
    var searching by mutableStateOf(false); private set
    var searchError by mutableStateOf<String?>(null); private set
    private var suggestJob: Job? = null

    // ---------- библиотека ----------
    var history by mutableStateOf<List<Track>>(emptyList()); private set
    var liked by mutableStateOf<List<Track>>(emptyList()); private set
    var playlists by mutableStateOf<List<LocalPlaylist>>(emptyList()); private set
    var openPl by mutableStateOf<LocalPlaylist?>(null); private set
    var openPlTracks by mutableStateOf<List<Track>>(emptyList()); private set

    // ---------- импорт плейлиста (из ВК или любого списка) ----------
    /** null — импорта нет; иначе экран ввода текста или проверки найденного. */
    var importStage by mutableStateOf<ImportStage?>(null); private set
    var importName by mutableStateOf("Из ВК"); private set
    var importRows by mutableStateOf<List<ImportRow>>(emptyList()); private set
    var importRunning by mutableStateOf(false); private set
    var importError by mutableStateOf<String?>(null); private set
    private var importJob: Job? = null
    /** Ссылка, которой поделились из ВК: открыть импорт по ней. */
    var sharedLink by mutableStateOf<String?>(null); private set

    /** Растёт при каждом «Поделиться» в «Волну» — интерфейс переключается на импорт. */
    var shareTick by mutableStateOf(0); private set

    fun onShared(text: String?) {
        shareTick++
        val link = com.volna.music.VkImportActivity.findVkLink(text)
        if (link != null) {
            sharedLink = link
        } else if (!text.isNullOrBlank() && PlaylistImport.parse(text).isNotEmpty()) {
            // Поделились самим списком треков
            cancelImport()
            startImport(text)
        } else {
            toast("Не нашёл ссылку на ВК")
        }
    }

    fun consumeSharedLink() { sharedLink = null }

    /** Список, считанный со страницы ВК. */
    fun importFromWeb(text: String, title: String) {
        if (title.isNotBlank()) importName = title.take(60)
        startImport(text)
    }

    // ---------- аккаунт YouTube Music ----------
    var ytLoggedIn by mutableStateOf(YtAccount.isLoggedIn); private set
    var ytName by mutableStateOf(YtAccount.accountName); private set
    var ytHome by mutableStateOf<List<Track>>(emptyList()); private set
    var ytPlaylists by mutableStateOf<List<YtPlaylist>>(emptyList()); private set
    var ytOpen by mutableStateOf<YtPlaylist?>(null); private set
    var ytOpenTracks by mutableStateOf<List<Track>>(emptyList()); private set
    var ytLoading by mutableStateOf(false); private set
    var ytError by mutableStateOf<String?>(null); private set

    // ---------- обновления ----------
    var update by mutableStateOf<UpdateInfo?>(null); private set
    var updateProgress by mutableStateOf<Float?>(null); private set

    private var lastUpdateCheck = 0L

    /** При возвращении в приложение; не чаще раза в полчаса. */
    fun checkUpdateOnResume() {
        if (System.currentTimeMillis() - lastUpdateCheck > 30 * 60_000L) checkUpdate()
    }

    /** [manual] — пользователь сам нажал «Проверить обновления»: тогда говорим и «обновлений нет». */
    fun checkUpdate(manual: Boolean = false) {
        lastUpdateCheck = System.currentTimeMillis()
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            try {
                update = Updater.check(ctx)
                if (manual && update == null) toast("У вас последняя версия")
            } catch (e: Exception) {
                if (manual) toast("Не удалось проверить: ${e.message}")
            }
        }
    }

    fun installUpdate() {
        val ctx = getApplication<Application>()
        val info = update ?: return
        if (updateProgress != null) return
        if (!Updater.canInstall(ctx)) {
            toast("Разрешите «Волне» устанавливать приложения и нажмите «Обновить» ещё раз")
            Updater.openInstallPermission(ctx)
            return
        }
        updateProgress = 0f
        viewModelScope.launch {
            try {
                val apk = Updater.download(ctx, info) { p -> viewModelScope.launch { updateProgress = p } }
                Updater.install(ctx, apk)
            } catch (e: Exception) {
                toast("Ошибка обновления: ${e.message}")
            } finally {
                updateProgress = null
            }
        }
    }

    // ---------- работа в фоне ----------
    private val uiPrefs = app.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)

    // ---------- радио под занятие и настроение ----------
    var radioActivity by mutableStateOf(
        runCatching { RadioActivity.valueOf(uiPrefs.getString("radio_activity", "") ?: "") }.getOrNull()
    ); private set
    var radioMood by mutableStateOf(
        runCatching { RadioMood.valueOf(uiPrefs.getString("radio_mood", "") ?: "") }.getOrNull()
    ); private set
    var radioLoading by mutableStateOf(false); private set

    /** Повторное нажатие на выбранное снимает выбор. */
    fun pickActivity(a: RadioActivity) {
        radioActivity = if (radioActivity == a) null else a
        uiPrefs.edit().putString("radio_activity", radioActivity?.name).apply()
    }

    fun pickMood(m: RadioMood) {
        radioMood = if (radioMood == m) null else m
        uiPrefs.edit().putString("radio_mood", radioMood?.name).apply()
    }

    fun startMoodRadio() {
        if (radioLoading) return
        val a = radioActivity
        val m = radioMood
        radioLoading = true
        viewModelScope.launch {
            try {
                val list = MoodRadio.tracks(db, a, m)
                if (list.isEmpty()) toast("Не удалось собрать радио — попробуйте ещё раз")
                else {
                    play(list)
                    toast("Радио: ${MoodRadio.title(a, m)}")
                }
            } catch (e: Exception) {
                toast(describeError(e).substringBefore("\n\n"))
            } finally {
                radioLoading = false
            }
        }
    }

    // ---------- видео на экране плеера ----------
    val video = VideoPreview(app, viewModelScope)
    var videoOn by mutableStateOf(uiPrefs.getBoolean("video_on", false)); private set
    private var videoVisible = false

    fun toggleVideo() {
        videoOn = !videoOn
        uiPrefs.edit().putBoolean("video_on", videoOn).apply()
        updateVideo()
    }

    /** Экран плеера (с обложкой) виден / скрыт: видео живёт только пока его видно. */
    fun setVideoVisible(visible: Boolean) {
        videoVisible = visible
        updateVideo()
    }

    private fun updateVideo() {
        val c = controller
        val id = nowPlaying?.id
        if (videoOn && videoVisible && c != null && id != null) {
            video.show(id, c.currentPosition.coerceAtLeast(0), c.isPlaying)
        } else {
            video.release()
        }
    }
    var batteryHint by mutableStateOf(false); private set

    private fun ignoringBattery(): Boolean {
        val pm = getApplication<Application>().getSystemService(android.os.PowerManager::class.java)
        return pm?.isIgnoringBatteryOptimizations(getApplication<Application>().packageName) ?: true
    }

    fun refreshBatteryHint() {
        batteryHint = !ignoringBattery() && !uiPrefs.getBoolean("battery_hint_closed", false)
    }

    /** Попросить систему не «усыплять» «Волну» в фоне — иначе многие телефоны глушат музыку через ~30 минут. */
    fun requestNoBatteryLimits() {
        val ctx = getApplication<Application>()
        val direct = android.content.Intent(
            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            android.net.Uri.parse("package:${ctx.packageName}"),
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val list = android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(direct) }.recoverCatching { ctx.startActivity(list) }
            .onFailure { toast("Откройте: Настройки → Батарея → Волна → «Без ограничений»") }
    }

    fun closeBatteryHint() {
        uiPrefs.edit().putBoolean("battery_hint_closed", true).apply()
        batteryHint = false
    }

    init {
        connect()
        checkUpdate()
        refreshBatteryHint()
        if (ytLoggedIn) loadYt()
        refreshHome()
        refreshLibrary()
        viewModelScope.launch {
            while (true) {
                controller?.let {
                    positionMs = it.currentPosition.coerceAtLeast(0)
                    if (it.duration > 0) durationMs = it.duration
                    if (videoOn && videoVisible) video.sync(positionMs, it.isPlaying)
                }
                delay(500)
            }
        }
    }

    private fun connect() {
        val ctx = getApplication<Application>()
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = runCatching { future.get() }
                .onFailure { com.volna.music.Diag.mark(ctx, "ui:controller FAILED ${it.javaClass.name}: ${it.message}") }
                .getOrNull() ?: return@addListener
            controller = c
            com.volna.music.Diag.mark(ctx, "ui:controller connected")
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = sync()
                override fun onPlayerError(error: PlaybackException) {
                    Toast.makeText(ctx, describeError(error).substringBefore("\n\n"), Toast.LENGTH_LONG).show()
                }
            })
            sync()
        }, ContextCompat.getMainExecutor(ctx))
    }

    private fun sync() {
        val c = controller ?: return
        val current = c.currentMediaItem?.let(MediaItems::track)
        val changed = current?.id != nowPlaying?.id
        nowPlaying = current
        isPlaying = c.isPlaying
        isBuffering = c.playbackState == Player.STATE_BUFFERING
        durationMs = c.duration.coerceAtLeast(0)
        queue = (0 until c.mediaItemCount).map { MediaItems.track(c.getMediaItemAt(it)) }
        queueIndex = c.currentMediaItemIndex
        shuffle = c.shuffleModeEnabled
        repeatMode = c.repeatMode
        if (changed) {
            updateVideo()
            val id = current?.id
            viewModelScope.launch {
                rating = if (id == null) 0 else withContext(Dispatchers.IO) { db.rating(id) }
                refreshLibrary()
            }
        }
    }

    override fun onCleared() {
        video.release()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onCleared()
    }

    // ---------- управление ----------

    /** Играть список с позиции; когда он кончится — продолжится радио. */
    fun play(list: List<Track>, index: Int = 0) {
        val c = controller ?: return
        if (list.isEmpty()) return
        c.setMediaItems(list.map(MediaItems::of), index.coerceIn(0, list.lastIndex), 0L)
        c.shuffleModeEnabled = false
        c.prepare()
        c.play()
    }

    /** Одна песня + бесконечное радио по ней, как в YouTube Music. */
    fun playRadio(t: Track) = play(listOf(t))

    fun playNext(t: Track) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return playRadio(t)
        c.addMediaItem(c.currentMediaItemIndex + 1, MediaItems.of(t))
        toast("Будет следующим")
    }

    fun addToQueue(t: Track) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return playRadio(t)
        c.addMediaItem(MediaItems.of(t))
        toast("Добавлено в очередь")
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.seekToPrevious() }
    fun seekTo(ms: Long) { controller?.seekTo(ms) }
    fun jumpTo(index: Int) { controller?.seekTo(index, 0L) }
    fun removeFromQueue(index: Int) { controller?.removeMediaItem(index) }
    fun toggleShuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    // ---------- оценки ----------

    fun toggleLike(t: Track) = rate(t, if (ratingOf(t) > 0) 0 else 1)

    /** «Не рекомендовать»: убираем из рекомендаций, и если играет — пропускаем. */
    fun dislike(t: Track) {
        rate(t, -1)
        if (nowPlaying?.id == t.id) next()
        forYou = forYou.filter { it.id != t.id }
        toast("Больше не будем предлагать")
    }

    // ---------- аккаунт ----------

    fun onLoginResult(ok: Boolean) {
        ytLoggedIn = YtAccount.isLoggedIn
        if (ok && ytLoggedIn) {
            toast("Вход выполнен")
            loadYt(fetchName = true)
        }
    }

    fun loadYt(fetchName: Boolean = false) {
        if (!ytLoggedIn || ytLoading) return
        ytLoading = true
        ytError = null
        viewModelScope.launch {
            try {
                if (fetchName || ytName == null) {
                    ytName = runCatching { YtAccount.fetchAccountName() }.getOrNull() ?: ytName
                }
                ytPlaylists = YtAccount.playlists()
                ytHome = runCatching { YtAccount.home() }.getOrDefault(ytHome)
            } catch (e: AuthExpiredException) {
                logoutYt()
                ytError = e.message
            } catch (e: Exception) {
                ytError = describeError(e)
            } finally {
                ytLoading = false
            }
        }
    }

    /** Открыть плейлист аккаунта; null = «Понравившиеся». */
    fun openYt(p: YtPlaylist?) {
        val pl = p ?: YtPlaylist("FEmusic_liked_videos", "Понравившиеся в YouTube", "", null)
        ytOpen = pl
        ytOpenTracks = emptyList()
        ytError = null
        viewModelScope.launch {
            try {
                ytOpenTracks = YtAccount.playlistTracks(pl.browseId)
                if (ytOpenTracks.isEmpty()) ytError = "Плейлист пуст"
            } catch (e: AuthExpiredException) {
                logoutYt(); ytError = e.message
            } catch (e: Exception) {
                ytError = describeError(e)
            }
        }
    }

    fun closeYt() { ytOpen = null; ytOpenTracks = emptyList() }

    fun logoutYt() {
        YtAccount.logout()
        ytLoggedIn = false
        ytName = null
        ytHome = emptyList()
        ytPlaylists = emptyList()
        closeYt()
    }

    private fun ratingOf(t: Track) = if (t.id == nowPlaying?.id) rating else
        if (liked.any { it.id == t.id }) 1 else 0

    private fun rate(t: Track, r: Int) {
        val wasLiked = ratingOf(t) > 0
        if (t.id == nowPlaying?.id) rating = r
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.setRating(t, r) }
            // лайк уходит и в аккаунт YouTube Music
            if (ytLoggedIn && (r > 0 || wasLiked)) {
                runCatching { YtAccount.setLike(t.id, r > 0) }
            }
            refreshLibrary()
        }
    }

    fun isLiked(t: Track) = liked.any { it.id == t.id }

    // ---------- главная ----------

    fun refreshHome() {
        if (homeLoading) return
        homeLoading = true
        homeError = null
        viewModelScope.launch {
            try {
                recent = withContext(Dispatchers.IO) { db.recent(15) }
                topArtists = withContext(Dispatchers.IO) { db.artistWeights(10).map { it.first } }
                forYou = Recommender.forYou(db)
                picks = runCatching { Picks.forYou(db, forYou) }.getOrDefault(forYou.take(15))
                if (forYou.isEmpty()) homeError = "YouTube ничего не вернул. Нажмите «Повторить».\n\n[${Yt.lastDiagnostics ?: "-"}; ${java.util.Locale.getDefault()}; ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}]"
            } catch (e: Exception) {
                homeError = describeError(e)
            } finally {
                homeLoading = false
            }
        }
    }

    // ---------- поиск ----------

    fun onQueryChange(q: String) {
        query = q
        suggestJob?.cancel()
        if (q.isBlank()) {
            suggestions = emptyList()
            return
        }
        suggestJob = viewModelScope.launch {
            delay(250)
            suggestions = Yt.suggestions(q).take(8)
        }
    }

    fun search(q: String = query) {
        val text = q.trim()
        if (text.isEmpty()) return
        query = text
        suggestJob?.cancel()
        suggestions = emptyList()
        searching = true
        searchError = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { db.addSearch(text) }
                results = Yt.searchSongs(text)
                if (results.isEmpty()) searchError = "Ничего не найдено\n\n[${Yt.lastDiagnostics ?: "-"}]"
            } catch (e: Exception) {
                searchError = describeError(e)
            } finally {
                searching = false
                loadRecentSearches()
            }
        }
    }

    fun clearQuery() {
        query = ""
        results = emptyList()
        suggestions = emptyList()
        searchError = null
    }

    fun deleteSearch(q: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.deleteSearch(q) }
            loadRecentSearches()
        }
    }

    fun loadRecentSearches() {
        viewModelScope.launch {
            recentSearches = withContext(Dispatchers.IO) { db.recentSearches(12) }
            playlists = withContext(Dispatchers.IO) { db.playlists() }
        }
    }

    // ---------- свои плейлисты ----------

    fun openPlaylist(p: LocalPlaylist) {
        openPl = p
        openPlTracks = emptyList()
        viewModelScope.launch { openPlTracks = withContext(Dispatchers.IO) { db.playlistTracks(p.id) } }
    }

    fun closePlaylist() { openPl = null; openPlTracks = emptyList() }

    fun removeFromPlaylist(t: Track) {
        val p = openPl ?: return
        openPlTracks = openPlTracks.filter { it.id != t.id }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.removeFromPlaylist(p.id, t.id) }
            refreshLibrary()
        }
    }

    fun deletePlaylist(p: LocalPlaylist) {
        closePlaylist()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.deletePlaylist(p.id) }
            refreshLibrary()
            toast("Плейлист удалён")
        }
    }

    // ---------- импорт ----------

    fun openImport() {
        if (importStage == null) importStage = ImportStage.INPUT
    }

    fun renameImport(name: String) { importName = name }

    /** Разобрать текст и начать искать треки на YouTube. */
    fun startImport(text: String) {
        val entries = PlaylistImport.parse(text)
        if (entries.isEmpty()) {
            toast("Не нашёл в тексте ни одного трека")
            return
        }
        importRows = entries.map { ImportRow(it.text, it.query) }
        importError = null
        importStage = ImportStage.REVIEW
        runSearch(importRows.indices.toList())
    }

    /** Ещё раз поискать ненайденные. */
    fun retryMissing() {
        if (importRunning) return
        runSearch(importRows.indices.filter { importRows[it].state.let { s -> s != ImportRow.State.OK && s != ImportRow.State.DOUBT } })
    }

    /** Поискать одну строку по исправленному запросу. */
    fun researchRow(i: Int, query: String) {
        val q = query.trim()
        if (q.isEmpty() || i !in importRows.indices) return
        updateRow(i) { it.copy(query = q, state = ImportRow.State.WAIT, options = emptyList(), pick = 0) }
        viewModelScope.launch { searchRow(i) }
    }

    fun pickOption(i: Int, option: Int) = updateRow(i) { it.copy(pick = option, state = ImportRow.State.OK, on = true) }

    fun toggleRow(i: Int) = updateRow(i) { it.copy(on = !it.on) }

    fun saveImport() {
        val list = importRows.filter { it.on }.mapNotNull { it.track }
        if (list.isEmpty()) { toast("Нечего сохранять"); return }
        val name = importName.trim().ifEmpty { "Из ВК" }
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) { db.createPlaylist(name, list) }
            cancelImport()
            refreshLibrary()
            toast("Сохранено: $name (${list.distinctBy { it.id }.size})")
            openPlaylist(LocalPlaylist(id, name, list.size, list.first().thumb))
        }
    }

    fun cancelImport() {
        importJob?.cancel()
        importJob = null
        importRunning = false
        importStage = null
        importRows = emptyList()
        importError = null
    }

    private fun updateRow(i: Int, f: (ImportRow) -> ImportRow) {
        val rows = importRows
        if (i !in rows.indices) return
        importRows = rows.toMutableList().also { it[i] = f(it[i]) }
    }

    private fun runSearch(indices: List<Int>) {
        if (indices.isEmpty()) return
        importJob?.cancel()
        importError = null
        importRunning = true
        importJob = viewModelScope.launch {
            // Не больше трёх запросов к YouTube одновременно — иначе он начинает просить капчу
            val gate = Semaphore(3)
            try {
                kotlinx.coroutines.coroutineScope {
                    indices.forEach { i ->
                        launch { gate.withPermit { searchRow(i); delay(150) } }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                importError = describeError(e).substringBefore("\n\n")
            } finally {
                importRunning = false
            }
        }
    }

    private suspend fun searchRow(i: Int) {
        val q = importRows.getOrNull(i)?.query ?: return
        var result: List<Track>? = null
        var failure: Exception? = null
        for (attempt in 0..1) {
            try {
                result = Yt.searchSongs(q).take(5)
                break
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
                // YouTube просит подтвердить, что мы не бот: дальше искать бесполезно
                if (isBotBlock(e)) throw e
                delay(1500)
            }
        }
        val found = result
        if (found == null) {
            updateRow(i) { it.copy(state = ImportRow.State.ERROR) }
            if (failure != null) importError = describeError(failure).substringBefore("\n\n")
            return
        }
        if (found.isEmpty()) {
            updateRow(i) { it.copy(state = ImportRow.State.NONE, options = emptyList()) }
            return
        }
        val scores = found.map { PlaylistImport.score(q, it) }
        val best = scores.indices.maxByOrNull { scores[it] } ?: 0
        val sure = scores[best] >= 0.6
        updateRow(i) {
            it.copy(
                options = found, pick = best,
                state = if (sure) ImportRow.State.OK else ImportRow.State.DOUBT,
            )
        }
    }

    // ---------- библиотека ----------

    fun refreshLibrary() {
        viewModelScope.launch {
            history = withContext(Dispatchers.IO) { db.recent(200) }
            liked = withContext(Dispatchers.IO) { db.liked() }
            recentSearches = withContext(Dispatchers.IO) { db.recentSearches(12) }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.clearHistory() }
            refreshLibrary()
            toast("История очищена")
        }
    }

    private fun toast(text: String) =
        Toast.makeText(getApplication(), text, Toast.LENGTH_SHORT).show()
}

/** Понятное описание ошибки + технические детали, чтобы можно было прислать скриншот. */
fun describeError(e: Throwable): String {
    var root: Throwable = e
    while (root.cause != null && root.cause !== root) root = root.cause!!
    val human = when {
        isBotBlock(e) ->
            "YouTube просит подтвердить, что вы не бот, — так бывает с VPN, некоторыми мобильными " +
                "операторами и серверами. Выключите VPN или смените сеть (Wi‑Fi ↔ мобильный интернет)."
        e is org.schabi.newpipe.extractor.exceptions.ReCaptchaException ||
            root is org.schabi.newpipe.extractor.exceptions.ReCaptchaException ->
            "YouTube временно ограничил запросы с этого IP (так бывает с мобильным интернетом и VPN). " +
                "Попробуйте Wi‑Fi, выключите VPN или подождите."
        root is java.net.UnknownHostException ->
            "Не удаётся найти сервер YouTube. Нет интернета, или его блокирует DNS / блокировщик рекламы / «Частный DNS»."
        root is javax.net.ssl.SSLException || root is java.security.cert.CertificateException ->
            "Ошибка защищённого соединения. Проверьте дату и время на телефоне, выключите VPN или фильтр трафика."
        root is java.net.SocketTimeoutException || root is java.net.ConnectException ->
            "Сервер YouTube не отвечает. Слабый интернет или соединение блокируется."
        else -> "Не удалось загрузить с YouTube."
    }
    return "$human\n\n[${root.javaClass.simpleName}: ${root.message ?: e.message ?: "-"}]"
}


/** YouTube временно заблокировал анонимный доступ с этого IP. */
fun isBotBlock(e: Throwable): Boolean {
    var t: Throwable? = e
    while (t != null) {
        if (t.javaClass.simpleName.contains("SignInConfirmNotBot") ||
            t is org.schabi.newpipe.extractor.exceptions.ReCaptchaException
        ) return true
        val c = t.cause
        t = if (c === t) null else c
    }
    return false
}
