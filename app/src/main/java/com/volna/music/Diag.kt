package com.volna.music

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Журнал шагов запуска. Если прошлый запуск не дошёл до «ui:ok» (упал, в том числе
 * нативно или в фоновом плеере), при следующем открытии покажем, на каком шаге это случилось.
 */
object Diag {
    private fun file(ctx: Context) = File(ctx.filesDir, "last_run.txt")
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Журнал прошлого запуска (читается один раз при старте процесса). */
    var previousRun: String? = null
        private set

    fun begin(ctx: Context) {
        previousRun = runCatching { file(ctx).takeIf { it.exists() }?.readText() }.getOrNull()
        runCatching {
            file(ctx).writeText(
                "Волна ${BuildConfigInfo.version(ctx)} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}" +
                    " · Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n",
            )
        }
        mark(ctx, "app:start")
    }

    fun mark(ctx: Context, step: String) {
        runCatching { file(ctx).appendText("${time.format(Date())} $step\n") }
    }

    fun clearPrevious() { previousRun = null }

    /** Прошлый запуск оборвался до того, как интерфейс нормально поработал. */
    fun previousFailed(): Boolean {
        val p = previousRun ?: return false
        return !p.contains("ui:ok")
    }
}
