package com.volna.music

import android.app.Application
import com.volna.music.data.MusicDb
import com.volna.music.net.OkDownloader
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.Locale

class App : Application() {
    lateinit var db: MusicDb
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashRecorder()
        Diag.begin(this)
        db = MusicDb(this)
        val locale = Locale.getDefault()
        val lang = locale.language.takeIf { it.matches(Regex("[a-z]{2,3}")) } ?: "en"
        val country = locale.country.takeIf { it.matches(Regex("[A-Z]{2}")) } ?: "US"
        try {
            NewPipe.init(OkDownloader.instance, Localization(lang, country), ContentCountry(country))
            Diag.mark(this, "app:newpipe ok")
        } catch (e: Throwable) {
            Diag.mark(this, "app:newpipe FAILED ${e.javaClass.name}: ${e.message}")
            NewPipe.init(OkDownloader.instance)
        }
    }

    /** «Чёрный ящик»: при падении сохраняем текст ошибки, чтобы показать его при следующем запуске. */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val text = buildString {
                    append("Волна ").append(BuildConfigInfo.version(this@App)).append('\n')
                    append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
                    append(", Android ").append(android.os.Build.VERSION.RELEASE)
                    append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
                    append("Поток: ").append(thread.name).append("\n\n")
                    append(android.util.Log.getStackTraceString(e))
                }
                crashFile(this).writeText(text.take(20_000))
                Diag.mark(this, "CRASH ${e.javaClass.name}: ${e.message?.take(200)}")
            }
            previous?.uncaughtException(thread, e)
        }
    }

    companion object {
        fun crashFile(ctx: android.content.Context) = java.io.File(ctx.filesDir, "last_crash.txt")

        lateinit var instance: App
            private set
    }
}

object BuildConfigInfo {
    fun version(ctx: android.content.Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    }.getOrNull() ?: "?"
}
