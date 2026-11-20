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

    /** Как на самом деле завершился прошлый процесс (Android 11+): true — упал/завис, false — закрыт системой. */
    private var lastExitBad: Boolean? = null

    fun begin(ctx: Context) {
        previousRun = runCatching { file(ctx).takeIf { it.exists() }?.readText() }.getOrNull()
        if (previousRun != null && android.os.Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val am = ctx.getSystemService(android.app.ActivityManager::class.java)
                val info = am?.getHistoricalProcessExitReasons(null, 0, 1)?.firstOrNull()
                if (info != null) {
                    val r = info.reason
                    lastExitBad = r == android.app.ApplicationExitInfo.REASON_CRASH ||
                        r == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE ||
                        r == android.app.ApplicationExitInfo.REASON_ANR ||
                        r == android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE
                    previousRun += "Как завершился: ${reasonName(r)}" +
                        (info.description?.let { " ($it)" } ?: "") + "\n"
                }
            }
        }
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
        if (p.contains("ui:ok")) return false
        lastExitBad?.let { return it }
        // Android 10 и старее: ошибка — только если записано падение или не открылся экран.
        // Запуск одного фонового плеера (шторка, Bluetooth, машина), который система потом
        // закрыла, ошибкой не считаем.
        return p.contains("CRASH") || p.contains("activity:create")
    }

    private fun reasonName(r: Int): String = when (r) {
        android.app.ApplicationExitInfo.REASON_CRASH -> "падение"
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "падение (нативное)"
        android.app.ApplicationExitInfo.REASON_ANR -> "зависание (ANR)"
        android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "ошибка запуска"
        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "закрыт системой — мало памяти"
        android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "закрыт пользователем"
        android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "остановлен пользователем"
        android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "завершился сам"
        android.app.ApplicationExitInfo.REASON_SIGNALED -> "остановлен системой"
        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "закрыт системой — много ресурсов"
        android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "изменены разрешения"
        android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "закрыт вместе с зависимостью"
        else -> "закрыт системой (код $r)"
    }
}
