package com.volna.music.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.volna.music.data.formatTime

@Composable
fun VolnaApp() {
    val appCtx = androidx.compose.ui.platform.LocalContext.current
    var theme by remember { mutableStateOf(ThemePrefs.load(appCtx)) }
    VolnaTheme(theme) {
        val vm: MainVm = viewModel()
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var playerOpen by rememberSaveable { mutableStateOf(false) }
        // Поделились плейлистом из ВК — открываем Библиотеку
        androidx.compose.runtime.LaunchedEffect(vm.shareTick) {
            if (vm.shareTick > 0) { tab = 2; playerOpen = false }
        }

        Box(Modifier.fillMaxSize()) {
            AppBackdrop(Modifier.matchParentSize())
            Scaffold(
                bottomBar = {
                    Column {
                        if (vm.nowPlaying != null) MiniPlayer(vm) { playerOpen = true }
                        NavigationBar {
                            NavigationBarItem(
                                selected = tab == 0, onClick = { tab = 0 },
                                icon = { Icon(Icons.Filled.Home, null) }, label = { Text("Главная") },
                            )
                            NavigationBarItem(
                                selected = tab == 1, onClick = { tab = 1 },
                                icon = { Icon(Icons.Filled.Search, null) }, label = { Text("Поиск") },
                            )
                            NavigationBarItem(
                                selected = tab == 2, onClick = { tab = 2 },
                                icon = { Icon(Icons.Filled.LibraryMusic, null) }, label = { Text("Библиотека") },
                            )
                        }
                    }
                },
            ) { pad ->
                Box(Modifier.padding(pad)) {
                    when (tab) {
                        0 -> HomeScreen(
                            vm,
                            onArtist = { a -> tab = 1; vm.search(a) },
                            theme = theme,
                            onTheme = { t -> theme = t; ThemePrefs.save(appCtx, t) },
                        )
                        1 -> SearchScreen(vm)
                        else -> LibraryScreen(vm)
                    }
                }
            }

            AnimatedVisibility(
                visible = playerOpen && vm.nowPlaying != null,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                FullPlayer(vm, onClose = { playerOpen = false })
            }
        }
        BackHandler(enabled = playerOpen) { playerOpen = false }
        val ctx = androidx.compose.ui.platform.LocalContext.current
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(5000)
            com.volna.music.Diag.mark(ctx, "ui:ok")
        }
    }
}

// ============================== Мини-плеер ==============================

@Composable
private fun MiniPlayer(vm: MainVm, onOpen: () -> Unit) {
    val t = vm.nowPlaying ?: return
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column {
            val progress = if (vm.durationMs > 0) vm.positionMs.toFloat() / vm.durationMs else 0f
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(t.thumb, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text(
                        t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PlayPauseButton(vm, big = false)
                IconButton(onClick = vm::next) { Icon(Icons.Filled.SkipNext, "Следующий") }
            }
        }
    }
}

@Composable
private fun PlayPauseButton(vm: MainVm, big: Boolean) {
    val icon = if (vm.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow
    if (big) {
        FilledIconButton(onClick = vm::togglePlay, modifier = Modifier.size(72.dp)) {
            if (vm.isBuffering && !vm.isPlaying) {
                CircularProgressIndicator(Modifier.size(32.dp), color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Icon(icon, "Пауза/Играть", Modifier.size(40.dp))
            }
        }
    } else {
        IconButton(onClick = vm::togglePlay) {
            if (vm.isBuffering && !vm.isPlaying) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Icon(icon, "Пауза/Играть")
            }
        }
    }
}

// ============================== Полный плеер ==============================

@Composable
private fun FullPlayer(vm: MainVm, onClose: () -> Unit) {
    val t = vm.nowPlaying ?: return
    var showQueue by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val ink = LocalPanelInk.current

    // смахивание вниз: экран едет за пальцем и сворачивается, если протянуть достаточно
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val dragY = remember { androidx.compose.animation.core.Animatable(0f) }
    val closeAt = with(androidx.compose.ui.platform.LocalDensity.current) { 140.dp.toPx() }
    val onDownDrag: (Float) -> Unit = { dy -> scope.launch { dragY.snapTo((dragY.value + dy).coerceAtLeast(0f)) } }
    val onDownEnd: () -> Unit = {
        if (dragY.value > closeAt) {
            onClose()
            scope.launch { kotlinx.coroutines.delay(400); dragY.snapTo(0f) }
        } else {
            scope.launch { dragY.animateTo(0f) }
        }
    }

    Surface(
        Modifier.fillMaxSize().graphicsLayer { translationY = dragY.value },
        color = MaterialTheme.colorScheme.background,
    ) {
      Box(Modifier.fillMaxSize()) {
        AppBackdrop(Modifier.matchParentSize())
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(4.dp).pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = onDownEnd,
                        onDragCancel = onDownEnd,
                        onVerticalDrag = { change, dy -> change.consume(); onDownDrag(dy) },
                    )
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.KeyboardArrowDown, "Свернуть", Modifier.size(32.dp))
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (showQueue) "Далее" else "Сейчас играет",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    sleepLabel(vm)?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                var sleepDialog by remember { mutableStateOf(false) }
                IconButton(onClick = { sleepDialog = true }, modifier = Modifier.size(56.dp)) {
                    val on = vm.sleepEndAt != null || vm.sleepEndOfTrack
                    Icon(
                        Icons.Filled.Bedtime, "Таймер сна", Modifier.size(28.dp),
                        tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (sleepDialog) SleepDialog(vm) { sleepDialog = false }
                IconButton(onClick = vm::toggleVideo, modifier = Modifier.size(56.dp)) {
                    Icon(
                        if (vm.videoOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                        if (vm.videoOn) "Выключить видео" else "Включить видео", Modifier.size(28.dp),
                        tint = if (vm.videoOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = { showQueue = !showQueue }, modifier = Modifier.size(56.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.QueueMusic, "Очередь", Modifier.size(28.dp),
                        tint = if (showQueue) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (showQueue) {
                    QueueList(vm)
                } else {
                    VideoVisibility(vm)
                    SwipeArtwork(vm, t.thumb, onDownDrag, onDownEnd)
                }
            }

            // название + сердечко + три точки
            Row(Modifier.padding(start = 24.dp, end = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        t.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    )
                    Text(
                        t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { vm.toggleLike(t) }, modifier = Modifier.size(56.dp)) {
                    if (vm.rating > 0) {
                        Icon(Icons.Filled.Favorite, "Убрать лайк", Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                    } else {
                        Icon(Icons.Filled.FavoriteBorder, "Нравится", Modifier.size(32.dp))
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Filled.MoreVert, "Меню", Modifier.size(32.dp))
                    }
                    TrackMenu(t, vm, expanded = menu, onDismiss = { menu = false })
                }
            }

            // сплошная белая панель управления с крупными кнопками
            Surface(
                color = Color.White,
                contentColor = ink,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp)) {
                    SeekBar(vm, ink)
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = vm::toggleShuffle, modifier = Modifier.size(56.dp)) {
                            Icon(
                                Icons.Filled.Shuffle, "Перемешать", Modifier.size(28.dp),
                                tint = if (vm.shuffle) ink else ink.copy(alpha = 0.35f),
                            )
                        }
                        IconButton(onClick = vm::previous, modifier = Modifier.size(76.dp)) {
                            Icon(Icons.Filled.SkipPrevious, "Предыдущий", Modifier.size(52.dp), tint = ink)
                        }
                        FilledIconButton(
                            onClick = vm::togglePlay,
                            modifier = Modifier.size(92.dp),
                            colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                                containerColor = ink, contentColor = Color.White,
                            ),
                        ) {
                            if (vm.isBuffering && !vm.isPlaying) {
                                CircularProgressIndicator(Modifier.size(40.dp), color = Color.White)
                            } else {
                                Icon(
                                    if (vm.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    "Пауза/Играть", Modifier.size(56.dp),
                                )
                            }
                        }
                        IconButton(onClick = vm::next, modifier = Modifier.size(76.dp)) {
                            Icon(Icons.Filled.SkipNext, "Следующий", Modifier.size(52.dp), tint = ink)
                        }
                        IconButton(onClick = vm::cycleRepeat, modifier = Modifier.size(56.dp)) {
                            val (icon, on) = when (vm.repeatMode) {
                                Player.REPEAT_MODE_ONE -> Icons.Filled.RepeatOne to true
                                Player.REPEAT_MODE_ALL -> Icons.Filled.Repeat to true
                                else -> Icons.Filled.Repeat to false
                            }
                            Icon(icon, "Повтор", Modifier.size(28.dp), tint = if (on) ink else ink.copy(alpha = 0.35f))
                        }
                    }
                }
            }
        }
      }
    }
}

/** Видео грузится, только пока экран плеера на виду и приложение открыто. */
@Composable
private fun VideoVisibility(vm: MainVm) {
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> vm.setVideoVisible(true)
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> vm.setVideoVisible(false)
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            vm.setVideoVisible(false)
        }
    }
}

/** Обложка, которую можно смахнуть: влево — следующий трек, вправо — предыдущий. */
@Composable
private fun SwipeArtwork(vm: MainVm, thumb: String?, onDownDrag: (Float) -> Unit, onDownEnd: () -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val offset = remember { androidx.compose.animation.core.Animatable(0f) }
    val threshold = with(androidx.compose.ui.platform.LocalDensity.current) { 90.dp.toPx() }
    Box(
        Modifier.fillMaxSize().padding(24.dp)
            .pointerInput(Unit) {
                // направление выбираем по первому движению: вбок — переключить трек, вниз — свернуть
                var axis = 0 // 0 — ещё не ясно, 1 — по горизонтали, 2 — по вертикали
                detectDragGestures(
                    onDragStart = { axis = 0 },
                    onDragEnd = {
                        if (axis == 1) {
                            val v = offset.value
                            when {
                                v < -threshold -> vm.next()
                                v > threshold -> vm.previous()
                            }
                            scope.launch { offset.animateTo(0f) }
                        } else if (axis == 2) {
                            onDownEnd()
                        }
                    },
                    onDragCancel = {
                        scope.launch { offset.animateTo(0f) }
                        if (axis == 2) onDownEnd()
                    },
                    onDrag = { change, drag ->
                        change.consume()
                        if (axis == 0) axis = if (kotlin.math.abs(drag.x) > kotlin.math.abs(drag.y)) 1 else 2
                        if (axis == 1) scope.launch { offset.snapTo(offset.value + drag.x) } else onDownDrag(drag.y)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val swipe = Modifier.graphicsLayer {
            translationX = offset.value
            rotationZ = offset.value / 60f
            alpha = 1f - (kotlin.math.abs(offset.value) / (threshold * 4)).coerceIn(0f, 0.5f)
        }
        val v = vm.video
        val videoPlayer = v.player
        if (vm.videoOn && videoPlayer != null) {
            // окно видео по пропорциям кадра, вписанное в место обложки, — не на весь экран
            Box(
                swipe.aspectRatio(v.aspect).clip(RoundedCornerShape(16.dp)).background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { android.view.TextureView(it) },
                    update = { tv -> videoPlayer.setVideoTextureView(tv) },
                    modifier = Modifier.fillMaxSize(),
                )
                if (v.status != com.volna.music.player.VideoPreview.Status.READY) {
                    AsyncImage(
                        model = thumb, contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    CircularProgressIndicator(Modifier.size(36.dp), color = Color.White)
                }
            }
        } else {
            Box(contentAlignment = Alignment.BottomCenter) {
                AsyncImage(
                    model = thumb,
                    contentDescription = "Обложка — смахните влево или вправо, чтобы переключить трек",
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = swipe.fillMaxWidth().aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                if (vm.videoOn) {
                    val note = when (v.status) {
                        com.volna.music.player.VideoPreview.Status.NONE -> "У этого трека нет видео"
                        com.volna.music.player.VideoPreview.Status.LOADING -> "Загружаем видео…"
                        else -> null
                    }
                    if (note != null) {
                        Text(
                            note,
                            Modifier.padding(12.dp).clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 10.dp, vertical = 4.dp),
                            color = Color.White, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SeekBar(vm: MainVm, ink: Color) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val dur = vm.durationMs.coerceAtLeast(1L)
    val value = if (dragging) dragValue else (vm.positionMs.toFloat() / dur).coerceIn(0f, 1f)

    Column {
        Slider(
            value = value,
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = {
                vm.seekTo((dragValue * dur).toLong())
                dragging = false
            },
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = ink, activeTrackColor = ink, inactiveTrackColor = ink.copy(alpha = 0.2f),
            ),
        )
        Row(Modifier.fillMaxWidth()) {
            val shownMs = if (dragging) (dragValue * dur).toLong() else vm.positionMs
            Text(formatTime(shownMs / 1000), style = MaterialTheme.typography.bodyMedium, color = ink)
            Spacer(Modifier.weight(1f))
            Text(formatTime(vm.durationMs / 1000), style = MaterialTheme.typography.bodyMedium, color = ink)
        }
    }
}

@Composable
private fun QueueList(vm: MainVm) {
    val state = rememberLazyListState()
    LaunchedEffect(Unit) { if (vm.queueIndex > 0) state.scrollToItem(vm.queueIndex) }
    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
        itemsIndexed(vm.queue) { i, t ->
            TrackRow(
                t, vm,
                onClick = { vm.jumpTo(i) },
                active = i == vm.queueIndex,
                onRemove = if (i != vm.queueIndex) ({ vm.removeFromQueue(i) }) else null,
            )
        }
    }
}


// ============================== Таймер сна ==============================

/** «⏾ 12:34» / «⏾ после трека» или null, если таймер не стоит. */
private fun sleepLabel(vm: MainVm): String? = when {
    vm.sleepEndOfTrack -> "⏾ после этого трека"
    vm.sleepEndAt != null -> "⏾ " + com.volna.music.data.formatTime((vm.sleepLeftMs + 999) / 1000)
    else -> null
}

@Composable
private fun SleepDialog(vm: MainVm, onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Bedtime, null) },
        title = { Text("Выключить музыку") },
        text = {
            Column {
                sleepLabel(vm)?.let {
                    Text("Сейчас: $it", color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                }
                val presets = listOf(10, 15, 30, 45, 60, 90, 120)
                presets.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                        row.forEach { m ->
                            androidx.compose.material3.OutlinedButton(
                                onClick = { vm.sleepIn(m); onDismiss() },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
                            ) { Text(if (m < 60 || m % 60 != 0) "$m мин" else "${m / 60} ч") }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.FilledTonalButton(
                    onClick = { vm.sleepAfterTrack(); onDismiss() }, modifier = Modifier.fillMaxWidth(),
                ) { Text("После этого трека") }
                Spacer(Modifier.height(4.dp))
                androidx.compose.material3.FilledTonalButton(
                    onClick = {
                        val now = java.util.Calendar.getInstance()
                        android.app.TimePickerDialog(
                            ctx,
                            { _, h, m -> vm.sleepAt(h, m) },
                            now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE), true,
                        ).show()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("В заданное время…") }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Последние 30 секунд громкость плавно стихает.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        dismissButton = if (vm.sleepEndAt != null || vm.sleepEndOfTrack) {
            { TextButton(onClick = { vm.sleepCancel(); onDismiss() }) { Text("Отключить таймер") } }
        } else null,
    )
}
