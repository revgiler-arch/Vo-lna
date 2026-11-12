package com.volna.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.volna.music.data.PlaylistImport
import com.volna.music.data.Track

enum class ImportStage { INPUT, REVIEW }

/** Строка импорта: что было в списке и что нашлось на YouTube. */
data class ImportRow(
    val text: String,
    val query: String,
    val state: State = State.WAIT,
    val options: List<Track> = emptyList(),
    val pick: Int = 0,
    val on: Boolean = true,
) {
    enum class State { WAIT, OK, DOUBT, NONE, ERROR }

    val track: Track? get() = options.getOrNull(pick)
}

private val pad = PaddingValues(bottom = 16.dp)

// ============================== Вкладка «Плейлисты» ==============================

@Composable
fun PlaylistsTab(vm: MainVm) {
    androidx.compose.runtime.LaunchedEffect(vm.sharedLink) {
        if (vm.sharedLink != null) vm.openImport()
    }
    if (vm.importStage == ImportStage.INPUT) return ImportInput(vm)
    if (vm.importStage == ImportStage.REVIEW) return ImportReview(vm)
    vm.openPl?.let { return PlaylistDetail(vm) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = pad) {
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Button(onClick = vm::openImport) {
                    Icon(Icons.Filled.Download, null); Spacer(Modifier.width(6.dp)); Text("Импорт из ВК / списка")
                }
            }
        }
        if (vm.playlists.isEmpty()) {
            item {
                Message(
                    "Здесь будут ваши плейлисты. Перенесите плейлист из ВК: скопируйте список треков " +
                        "и «Волна» найдёт каждый на YouTube.",
                )
            }
        }
        items(vm.playlists, key = { "pl" + it.id }) { p ->
            PlaylistRow(p.name, "${p.count} ${tracksWord(p.count)}", p.thumb) { vm.openPlaylist(p) }
        }
    }
}

@Composable
private fun PlaylistDetail(vm: MainVm) {
    val p = vm.openPl ?: return
    var confirm by remember { mutableStateOf(false) }
    val list = vm.openPlTracks
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pad) {
        item {
            Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = vm::closePlaylist) { Text("← Назад") }
                Text(
                    p.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = { confirm = true }) { Icon(Icons.Filled.Delete, "Удалить плейлист") }
            }
        }
        if (confirm) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Удалить «${p.name}»?", Modifier.weight(1f))
                    TextButton(onClick = { confirm = false }) { Text("Нет") }
                    TextButton(onClick = { confirm = false; vm.deletePlaylist(p) }) { Text("Да") }
                }
            }
        }
        if (list.isNotEmpty()) {
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
        itemsIndexed(list, key = { _, t -> "pt" + t.id }) { i, t ->
            TrackRow(t, vm, onClick = { vm.play(list, i) }, onRemove = { vm.removeFromPlaylist(t) })
        }
    }
}

// ============================== Импорт: ввод ==============================

@Composable
private fun ImportInput(vm: MainVm) {
    var text by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val count = remember(text) { PlaylistImport.parse(text).size }
    var link by rememberSaveable { mutableStateOf("") }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val web = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { r ->
        val got = r.data?.getStringExtra(com.volna.music.VkImportActivity.EXTRA_TEXT)
        if (r.resultCode == android.app.Activity.RESULT_OK && !got.isNullOrBlank()) {
            vm.importFromWeb(got, r.data?.getStringExtra(com.volna.music.VkImportActivity.EXTRA_TITLE).orEmpty())
        }
    }
    fun openLink(url: String) {
        val u = url.trim().let { if (it.startsWith("http")) it else "https://$it" }
        web.launch(
            android.content.Intent(ctx, com.volna.music.VkImportActivity::class.java)
                .putExtra(com.volna.music.VkImportActivity.EXTRA_URL, u),
        )
    }
    androidx.compose.runtime.LaunchedEffect(vm.sharedLink) {
        vm.sharedLink?.let { link = it; vm.consumeSharedLink(); openLink(it) }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = pad) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = vm::cancelImport) { Text("← Отмена") }
                Text("Импорт плейлиста", style = MaterialTheme.typography.titleMedium)
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("По ссылке (проще всего)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "В ВК: плейлист → «Поделиться» → «Волна». Или скопируйте ссылку (из QR-кода — " +
                        "наведите камеру, она покажет ссылку) и вставьте сюда. Откроется ВК: один раз войдите, " +
                        "затем нажмите «Считать» — «Волна» сама прокрутит плейлист и заберёт все треки.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = link, onValueChange = { link = it }, singleLine = true,
                        label = { Text("Ссылка на плейлист") }, modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        val c = clipboard.getText()?.text
                        link = com.volna.music.VkImportActivity.findVkLink(c) ?: c?.trim().orEmpty()
                    }) { Icon(Icons.Filled.ContentPaste, "Вставить ссылку") }
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { openLink(link) }, enabled = link.isNotBlank()) { Text("Открыть и считать") }
                Spacer(Modifier.height(20.dp))
                Text("Или вставьте список текстом", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Как скопировать из ВК:\n" +
                        "1. Откройте плейлист на vk.com в браузере (на телефоне — «Версия для ПК»).\n" +
                        "2. Прокрутите до конца, чтобы загрузились все треки.\n" +
                        "3. Выделите список от первого трека до последнего и скопируйте.\n" +
                        "4. Нажмите «Вставить» ниже.\n\n" +
                        "Подойдёт и любой список вида «Исполнитель — Название», по треку на строку.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = vm.importName, onValueChange = vm::renameImport,
                    label = { Text("Название плейлиста") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { clipboard.getText()?.text?.let { text = it } }) {
                    Icon(Icons.Filled.ContentPaste, null); Spacer(Modifier.width(6.dp)); Text("Вставить")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("Список треков") },
                    minLines = 6, maxLines = 14,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (text.isBlank()) "" else "Распознано треков: $count",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { vm.startImport(text) }, enabled = count > 0) {
                    Icon(Icons.Filled.Search, null); Spacer(Modifier.width(6.dp))
                    Text(if (count > 0) "Найти треки ($count)" else "Найти треки")
                }
            }
        }
    }
}

// ============================== Импорт: проверка ==============================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportReview(vm: MainVm) {
    val rows = vm.importRows
    var filter by rememberSaveable { mutableIntStateOf(0) } // 0 все, 1 проверить, 2 не найдено
    var open by rememberSaveable { mutableIntStateOf(-1) }

    val done = rows.count { it.state != ImportRow.State.WAIT }
    val ok = rows.count { it.state == ImportRow.State.OK }
    val doubt = rows.count { it.state == ImportRow.State.DOUBT }
    val missing = rows.count { it.state == ImportRow.State.NONE || it.state == ImportRow.State.ERROR }
    val toSave = rows.count { it.on && it.track != null }

    val shown = rows.indices.filter { i ->
        when (filter) {
            1 -> rows[i].state == ImportRow.State.DOUBT
            2 -> rows[i].state == ImportRow.State.NONE || rows[i].state == ImportRow.State.ERROR
            else -> true
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = pad) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = vm::cancelImport) { Text("← Отмена") }
                Text(
                    vm.importName, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                if (vm.importRunning) {
                    Text("Ищу на YouTube: $done из ${rows.size}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { if (rows.isEmpty()) 0f else done.toFloat() / rows.size },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Можно уйти на другие экраны и слушать музыку — поиск продолжится.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "Найдено $ok · проверить $doubt · не найдено $missing",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                vm.importError?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::saveImport, enabled = !vm.importRunning && toSave > 0) {
                        Text("Сохранить ($toSave)")
                    }
                    if (!vm.importRunning && missing + rows.count { it.state == ImportRow.State.WAIT } > 0) {
                        OutlinedButton(onClick = vm::retryMissing) { Text("Искать ненайденные") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = filter == 0, onClick = { filter = 0 }, label = { Text("Все ${rows.size}") })
                    FilterChip(selected = filter == 1, onClick = { filter = 1 }, label = { Text("Проверить $doubt") })
                    FilterChip(selected = filter == 2, onClick = { filter = 2 }, label = { Text("Нет $missing") })
                }
                if (doubt > 0 && !vm.importRunning) {
                    Text(
                        "«?» — не уверен, что нашёл то самое. Нажмите на строку, чтобы выбрать другой вариант или исправить запрос.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(shown, key = { "ir$it" }) { i ->
            ImportRowView(vm, i, rows[i], expanded = open == i, onToggleOpen = { open = if (open == i) -1 else i })
        }
    }
}

@Composable
private fun ImportRowView(vm: MainVm, i: Int, row: ImportRow, expanded: Boolean, onToggleOpen: () -> Unit) {
    val t = row.track
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggleOpen).padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = row.on && t != null, onCheckedChange = { vm.toggleRow(i) }, enabled = t != null)
            Artwork(t?.thumb, 44.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    t?.let { "${it.title} · ${it.artist}" } ?: row.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (t != null) FontWeight.Normal else FontWeight.Light,
                )
                Text(
                    "было: " + row.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val (icon, tint) = when (row.state) {
                ImportRow.State.WAIT -> Icons.Filled.HourglassEmpty to MaterialTheme.colorScheme.onSurfaceVariant
                ImportRow.State.OK -> Icons.Filled.CheckCircle to MaterialTheme.colorScheme.primary
                ImportRow.State.DOUBT -> Icons.Filled.HelpOutline to MaterialTheme.colorScheme.tertiary
                ImportRow.State.NONE, ImportRow.State.ERROR -> Icons.Filled.ErrorOutline to MaterialTheme.colorScheme.error
            }
            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        }
        if (expanded) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                row.options.forEachIndexed { k, o ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.pickOption(i, k) }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = k == row.pick, onClick = { vm.pickOption(i, k) })
                        Artwork(o.thumb, 36.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${o.title} · ${o.artist}", Modifier.weight(1f),
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        IconButton(onClick = { vm.play(listOf(o)) }) { Icon(Icons.Filled.PlayArrow, "Послушать") }
                    }
                }
                if (row.options.isEmpty() && row.state != ImportRow.State.WAIT) {
                    Text("Ничего не нашлось. Исправьте запрос:", style = MaterialTheme.typography.bodySmall)
                }
                var q by remember(row.query) { mutableStateOf(row.query) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = q, onValueChange = { q = it }, singleLine = true,
                        label = { Text("Запрос") }, modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { vm.researchRow(i, q) }) { Icon(Icons.Filled.Search, "Искать") }
                }
            }
        }
    }
}

private fun tracksWord(n: Int): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> "трек"
        m10 in 2..4 && m100 !in 12..14 -> "трека"
        else -> "треков"
    }
}
