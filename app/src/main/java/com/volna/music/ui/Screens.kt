package com.volna.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.foundation.layout.size
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.volna.music.data.Track

private val listPadding = PaddingValues(bottom = 16.dp)

// ============================== Главная ==============================

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainVm, onArtist: (String) -> Unit, theme: AppTheme, onTheme: (AppTheme) -> Unit) {
    // свайп вниз = «Обновить»
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = vm.homeLoading,
        onRefresh = { vm.refreshHome(); vm.loadYt() },
        modifier = Modifier.fillMaxSize(),
    ) {
        HomeList(vm, onArtist, theme, onTheme)
    }
}

@Composable
private fun HomeList(vm: MainVm, onArtist: (String) -> Unit, theme: AppTheme, onTheme: (AppTheme) -> Unit) {
    var themeMenu by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding) {
        item {
            SectionTitle("Волна") {
                Box {
                    IconButton(onClick = { themeMenu = true }) { Icon(Icons.Filled.Palette, "Оформление") }
                    androidx.compose.material3.DropdownMenu(expanded = themeMenu, onDismissRequest = { themeMenu = false }) {
                        AppTheme.entries.forEach { t ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(t.title) },
                                leadingIcon = {
                                    Icon(
                                        if (t == theme) Icons.Filled.CheckCircle else Icons.Filled.Circle, null,
                                        tint = when (t) {
                                            AppTheme.DARK -> androidx.compose.ui.graphics.Color(0xFF26262D)
                                            AppTheme.BLUE -> androidx.compose.ui.graphics.Color(0xFF3D5AFE)
                                            AppTheme.EMERALD -> androidx.compose.ui.graphics.Color(0xFF12A37A)
                                            AppTheme.RGB -> androidx.compose.ui.graphics.Color(0xFFFF2D95)
                                            AppTheme.RGB_BEAT -> androidx.compose.ui.graphics.Color(0xFFFFB300)
                                        },
                                    )
                                },
                                onClick = { themeMenu = false; onTheme(t) },
                            )
                        }
                        androidx.compose.material3.HorizontalDivider()
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Работа в фоне без ограничений") },
                            leadingIcon = { Icon(Icons.Filled.BatteryChargingFull, null) },
                            onClick = { themeMenu = false; vm.requestNoBatteryLimits() },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Проверить обновления") },
                            leadingIcon = { Icon(Icons.Filled.SystemUpdate, null) },
                            onClick = { themeMenu = false; vm.checkUpdate(manual = true) },
                        )
                    }
                }
                IconButton(onClick = vm::refreshHome, enabled = !vm.homeLoading) {
                    Icon(Icons.Filled.Refresh, "Обновить")
                }
            }
        }

        if (vm.batteryHint) {
            item {
                androidx.compose.material3.Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Чтобы музыка не останавливалась в фоне", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Телефон может «усыплять» «Волну» через полчаса. Разрешите ей работать без ограничений батареи.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = vm::requestNoBatteryLimits) { Text("Разрешить") }
                            TextButton(onClick = vm::closeBatteryHint) { Text("Не сейчас") }
                        }
                    }
                }
            }
        }

        vm.update?.let { up ->
            item {
                androidx.compose.material3.Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.SystemUpdate, null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Доступна новая версия", style = MaterialTheme.typography.titleSmall)
                            val p = vm.updateProgress
                            if (p != null) {
                                androidx.compose.material3.LinearProgressIndicator(
                                    progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                )
                            } else {
                                Text("Сборка ${up.versionCode}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Button(onClick = vm::installUpdate, enabled = vm.updateProgress == null) { Text("Обновить") }
                    }
                }
            }
        }

        if (vm.recent.isNotEmpty() && vm.picks.isNotEmpty()) {
            item { SectionTitle("Рекомендуем") }
            item {
                Text(
                    "Новое у исполнителей, которых вы слушаете",
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(vm.picks, key = { _, t -> "pk" + t.id }) { i, t -> RecentCard(t) { vm.play(vm.picks, i) } }
                }
            }
        }

        item { RadioCard(vm) }

        if (vm.topArtists.isNotEmpty()) {
            item { SectionTitle("Ваши исполнители") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(vm.topArtists) { a -> AssistChip(onClick = { onArtist(a) }, label = { Text(a) }) }
                }
            }
        }

        item {
            SectionTitle(if (vm.recent.isEmpty()) "Популярное" else "Для вас")
        }
        if (vm.forYou.isNotEmpty()) {
            item {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.play(vm.forYou) }) {
                        Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Слушать")
                    }
                    FilledTonalButton(onClick = { vm.play(vm.forYou.shuffled()) }) {
                        Icon(Icons.Filled.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("Вперемешку")
                    }
                }
            }
        }
        when {
            vm.homeError != null && vm.forYou.isEmpty() -> item {
                Column {
                    Message(vm.homeError!!)
                    Button(onClick = vm::refreshHome, modifier = Modifier.padding(horizontal = 24.dp)) {
                        Text("Повторить")
                    }
                }
            }
        }
        itemsIndexed(vm.forYou, key = { _, t -> "fy" + t.id }) { i, t ->
            TrackRow(t, vm, onClick = { vm.play(vm.forYou, i) })
        }
        if (vm.recent.isEmpty() && !vm.homeLoading) {
            item {
                Message("Слушайте музыку — лента «Для вас» будет подстраиваться под вашу историю.")
            }
        }
    }
}

// ============================== Радио ==============================

/** Карточка «Радио»: по нажатию раскрывается меню — занятие и настроение. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RadioCard(vm: MainVm) {
    var open by rememberSaveable { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    androidx.compose.material3.Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Radio, null, Modifier.size(36.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Радио", style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text(
                    if (vm.radioActivity == null && vm.radioMood == null) "Под занятие и настроение"
                    else com.volna.music.data.MoodRadio.title(vm.radioActivity, vm.radioMood),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Icon(
                if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                if (open) "Свернуть" else "Настроить", Modifier.size(32.dp),
            )
        }
        AnimatedVisibility(visible = open) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                Text("Чем заняты", style = MaterialTheme.typography.titleSmall)
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.padding(top = 6.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    com.volna.music.data.RadioActivity.entries.forEach { a ->
                        RadioChip(a.title, vm.radioActivity == a) { vm.pickActivity(a) }
                    }
                }
                Text("Настроение", style = MaterialTheme.typography.titleSmall)
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.padding(top = 6.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    com.volna.music.data.RadioMood.entries.forEach { m ->
                        RadioChip(m.title, vm.radioMood == m) { vm.pickMood(m) }
                    }
                }
                Button(
                    onClick = { vm.startMoodRadio(); open = false },
                    enabled = !vm.radioLoading,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    if (vm.radioLoading) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp))
                        Text("Включить радио")
                    }
                }
            }
        }
    }
}

@Composable
private fun RadioChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val check: (@Composable () -> Unit)? = if (selected) {
        { Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) }
    } else null
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyLarge) },
        leadingIcon = check,
        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
            labelColor = scheme.onPrimaryContainer,
            selectedContainerColor = scheme.primary,
            selectedLabelColor = scheme.onPrimary,
            selectedLeadingIconColor = scheme.onPrimary,
        ),
        border = androidx.compose.material3.FilterChipDefaults.filterChipBorder(
            enabled = true, selected = selected,
            borderColor = scheme.onPrimaryContainer.copy(alpha = 0.5f),
        ),
    )
}

@Composable
internal fun RecentCard(t: Track, onClick: () -> Unit) {
    Column(Modifier.width(120.dp).clickable(onClick = onClick)) {
        Artwork(t.thumb, 120.dp, corner = 10.dp)
        Spacer(Modifier.height(6.dp))
        Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        Text(
            t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ============================== Поиск ==============================

@Composable
fun SearchScreen(vm: MainVm) {
    val focus = LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val done = { focus.clearFocus(); keyboard?.hide(); Unit }
    LaunchedEffect(Unit) { vm.loadRecentSearches() }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = vm.query,
            onValueChange = vm::onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            placeholder = { Text("Песни, исполнители") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (vm.query.isNotEmpty()) {
                    IconButton(onClick = vm::clearQuery) { Icon(Icons.Filled.Close, "Очистить") }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.search(); done() }),
        )

        LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding) {
            // подсказки при наборе
            if (vm.suggestions.isNotEmpty()) {
                items(vm.suggestions) { s ->
                    SuggestionRow(s, Icons.Filled.Search) { vm.search(s); done() }
                }
                return@LazyColumn
            }
            // история поиска, пока нет результатов
            if (vm.results.isEmpty() && !vm.searching && vm.searchError == null) {
                if (vm.recentSearches.isNotEmpty()) item { SectionTitle("Вы искали") }
                items(vm.recentSearches) { s ->
                    SuggestionRow(s, Icons.Filled.History, onDelete = { vm.deleteSearch(s) }) {
                        vm.search(s); done()
                    }
                }
                return@LazyColumn
            }
            if (vm.searching) item { Loading() }
            vm.searchError?.let { e -> item { Message(e) } }
            itemsIndexed(vm.results, key = { _, t -> "sr" + t.id }) { _, t ->
                TrackRow(t, vm, onClick = { done(); vm.playRadio(t) })
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onDelete: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(text, Modifier.weight(1f).padding(vertical = 14.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (onDelete != null) {
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Close, "Удалить") }
        }
    }
}

// ============================== Библиотека ==============================

@Composable
fun LibraryScreen(vm: MainVm) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refreshLibrary() }

    Column(Modifier.fillMaxSize()) {
        SectionTitle("Библиотека") {
            if (tab == 1 && vm.history.isNotEmpty()) {
                IconButton(onClick = { confirmClear = true }) { Icon(Icons.Filled.DeleteSweep, "Очистить историю") }
            }
        }
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Любимые") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("История") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("YouTube") })
        }
        if (tab == 2) {
            YtAccountTab(vm)
            return@Column
        }
        if (confirmClear) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Очистить историю? Рекомендации начнутся заново.", Modifier.weight(1f))
                TextButton(onClick = { confirmClear = false }) { Text("Нет") }
                TextButton(onClick = { confirmClear = false; vm.clearHistory() }) { Text("Да") }
            }
        }
        val list = if (tab == 0) vm.liked else vm.history
        LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding) {
            if (list.isEmpty()) {
                item {
                    Message(
                        if (tab == 0) "Здесь будут треки, которым вы поставили ♥"
                        else "Здесь будет всё, что вы слушали",
                    )
                }
            } else {
                item {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.play(list) }) {
                            Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Слушать")
                        }
                        FilledTonalButton(onClick = { vm.play(list.shuffled()) }) {
                            Icon(Icons.Filled.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("Вперемешку")
                        }
                    }
                }
            }
            itemsIndexed(list, key = { _, t -> "lb$tab" + t.id }) { i, t ->
                TrackRow(t, vm, onClick = { vm.play(list, i) })
            }
        }
    }
}

// ============================== Мелочи ==============================

@Composable
fun Loading() {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun Message(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(24.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}


// ============================== Аккаунт YouTube ==============================

@Composable
private fun YtAccountTab(vm: MainVm) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val login = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { vm.onLoginResult(it.resultCode == android.app.Activity.RESULT_OK) }

    if (!vm.ytLoggedIn) {
        Column(Modifier.fillMaxWidth().padding(24.dp)) {
            Text(
                "Войдите в Google-аккаунт, чтобы видеть свои лайки и плейлисты из YouTube Music " +
                    "и получать его персональные подборки. Лайки из «Волны» тоже будут сохраняться в аккаунт.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Данные входа хранятся только на этом телефоне. Совет: используйте отдельный аккаунт для музыки.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            vm.ytError?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(16.dp))
            Button(onClick = { login.launch(android.content.Intent(ctx, com.volna.music.LoginActivity::class.java)) }) {
                Text("Войти через Google")
            }
        }
        return
    }

    val open = vm.ytOpen
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Аккаунт: " + (vm.ytName ?: "вход выполнен"),
                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                )
                IconButton(onClick = { vm.loadYt() }) { Icon(Icons.Filled.Refresh, "Обновить") }
                TextButton(onClick = vm::logoutYt) { Text("Выйти") }
            }
        }
        if (open != null) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = vm::closeYt) { Text("← Назад") }
                    Text(open.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (vm.ytOpenTracks.isNotEmpty()) {
                item {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.play(vm.ytOpenTracks) }) {
                            Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Слушать")
                        }
                        FilledTonalButton(onClick = { vm.play(vm.ytOpenTracks.shuffled()) }) {
                            Icon(Icons.Filled.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("Вперемешку")
                        }
                    }
                }
            } else if (vm.ytError == null) {
                item { Loading() }
            }
            vm.ytError?.let { e -> item { Message(e) } }
            itemsIndexed(vm.ytOpenTracks, key = { _, t -> "yt" + t.id }) { i, t ->
                TrackRow(t, vm, onClick = { vm.play(vm.ytOpenTracks, i) })
            }
            return@LazyColumn
        }

        item {
            PlaylistRow("Понравившиеся в YouTube", "Все ваши лайки", null) { vm.openYt(null) }
        }
        if (vm.ytLoading && vm.ytPlaylists.isEmpty()) item { Loading() }
        vm.ytError?.let { e -> item { Message(e) } }
        items(vm.ytPlaylists) { p ->
            PlaylistRow(p.title, p.subtitle, p.thumb) { vm.openYt(p) }
        }
    }
}

@Composable
private fun PlaylistRow(title: String, subtitle: String, thumb: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(thumb, 56.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
