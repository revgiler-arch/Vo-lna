package com.volna.music.data

/**
 * Импорт плейлиста из текста: список, скопированный со страницы ВК (или любой
 * «Исполнитель — Название» по строке), превращаем в поисковые запросы,
 * а потом каждый ищем на YouTube.
 */
object PlaylistImport {

    /** Одна строка будущего плейлиста: [text] показываем, по [query] ищем. */
    data class Entry(val text: String, val query: String)

    private val duration = Regex("""^\d{1,2}:\d{2}(:\d{2})?$""")
    private val trailingDuration = Regex("""\s+\d{1,2}:\d{2}(:\d{2})?$""")
    private val numberPrefix = Regex("""^\d{1,4}\s*[.)]\s+""")
    private val separators = Regex("""\s+[—–-]\s+|\s*[—–]\s*""")
    private val junk = setOf(
        "e", "explicit", "слушать", "добавить", "скачать", "hq", "...", "…", "•", "·",
        "показать все", "показать всё", "ещё", "еще", "перемешать", "воспроизвести",
        "моя музыка", "плейлист", "альбом", "треки", "аудиозаписи",
    )

    private enum class Kind { BLANK, DURATION, TEXT }

    private class Line(val kind: Kind, val text: String = "")

    fun parse(input: String): List<Entry> {
        val lines = input.lines().map { raw ->
            val s = raw.replace(' ', ' ').replace(Regex("""\s+"""), " ").trim()
            when {
                s.isEmpty() -> Line(Kind.BLANK)
                duration.matches(s) -> Line(Kind.DURATION)
                s.lowercase() in junk -> Line(Kind.BLANK)
                else -> {
                    val clean = s.replace(numberPrefix, "").replace(trailingDuration, "").trim()
                    if (clean.isEmpty() || clean.lowercase() in junk) Line(Kind.BLANK) else Line(Kind.TEXT, clean)
                }
            }
        }
        val texts = lines.filter { it.kind == Kind.TEXT }
        if (texts.isEmpty()) return emptyList()

        val durations = lines.count { it.kind == Kind.DURATION }
        val withSep = texts.count { separators.containsMatchIn(it.text) }

        val groups: List<List<String>> = when {
            // Каждая строка уже «Исполнитель — Название»
            withSep * 2 >= texts.size -> texts.map { listOf(it.text) }
            // Страница ВК: название, исполнитель, длительность — длительность закрывает трек
            durations * 3 >= texts.size -> splitBy(lines) { it.kind == Kind.DURATION }
            // Треки разделены пустыми строками
            blankBlocks(lines) -> splitBy(lines) { it.kind == Kind.BLANK }
            // Иначе считаем, что строки идут парами
            else -> texts.map { it.text }.chunked(2)
        }

        return groups.mapNotNull { g ->
            val parts = g.take(2)
            if (parts.isEmpty()) return@mapNotNull null
            val text = parts.joinToString(" — ")
            val query = parts.joinToString(" ") { it.replace(separators, " ") }.trim()
            if (query.length < 2) null else Entry(text, query)
        }.distinctBy { it.query.lowercase() }.take(1000)
    }

    private fun splitBy(lines: List<Line>, isEnd: (Line) -> Boolean): List<List<String>> {
        val out = mutableListOf<List<String>>()
        var cur = mutableListOf<String>()
        for (l in lines) {
            if (isEnd(l)) {
                if (cur.isNotEmpty()) out += cur
                cur = mutableListOf()
            } else if (l.kind == Kind.TEXT) {
                cur += l.text
            }
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    /** Блоки по 1–3 строки, разделённые пустыми строками. */
    private fun blankBlocks(lines: List<Line>): Boolean {
        val blocks = splitBy(lines) { it.kind == Kind.BLANK }
        return blocks.size >= 2 && blocks.all { it.size in 1..3 } && blocks.count { it.size == 2 } * 2 >= blocks.size
    }

    // ---------- насколько найденное похоже на то, что искали ----------

    private fun words(s: String): Set<String> =
        s.lowercase().replace('ё', 'е')
            .split(Regex("""[^\p{L}\p{N}]+"""))
            .filter { it.length >= 2 && it !in noise }
            .toSet()

    private val noise = setOf(
        "official", "video", "audio", "lyrics", "lyric", "music", "topic", "feat", "ft",
        "клип", "премьера", "remastered", "hd", "hq", "the",
    )

    /** 0..1: какая доля слов запроса нашлась в названии и исполнителе. */
    fun score(query: String, t: Track): Double {
        val q = words(query)
        if (q.isEmpty()) return 0.0
        val found = words(t.title + " " + t.artist)
        return q.count { w -> w in found || found.any { it.startsWith(w) || w.startsWith(it) && it.length >= 4 } }
            .toDouble() / q.size
    }
}
