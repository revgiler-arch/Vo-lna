package com.volna.music

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.volna.music.ui.VolnaApp

class MainActivity : ComponentActivity() {
    // регистрировать нужно до onStart — поэтому полем, а запускать уже в startUi()
    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private var uiStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diag.mark(this, "activity:create")
        // Прошлый запуск упал — сначала показываем отчёт простым системным окном (без Compose)
        val crash = runCatching { App.crashFile(this).takeIf { it.exists() }?.readText() }.getOrNull()
        if (savedInstanceState == null && (crash != null || Diag.previousFailed())) {
            showCrashReport(buildString {
                if (crash != null) append(crash).append("\n\n")
                append("Шаги прошлого запуска:\n").append(Diag.previousRun ?: "-")
            })
            return
        }
        startUi()
    }

    private fun showCrashReport(report: String) {
        val view = android.widget.TextView(this).apply {
            text = report
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(40, 24, 40, 24)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Прошлый запуск завершился ошибкой")
            .setView(android.widget.ScrollView(this).apply { addView(view) })
            .setCancelable(false)
            .setPositiveButton("Скопировать") { _, _ ->
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("Волна: ошибка", report))
                android.widget.Toast.makeText(this, "Скопировано — пришлите в чат", android.widget.Toast.LENGTH_LONG).show()
                clearAndStart()
            }
            .setNegativeButton("Продолжить") { _, _ -> clearAndStart() }
            .show()
    }

    private fun clearAndStart() {
        runCatching { App.crashFile(this).delete() }
        Diag.clearPrevious()
        startUi()
    }

    private fun startUi() {
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        if (uiStarted) return
        uiStarted = true
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching { notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
        setContent { VolnaApp() }
        Diag.mark(this, "activity:content set")
        handleShare(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (uiStarted) handleShare(intent)
    }

    /** «Поделиться» из ВК: ссылка на плейлист или сам список. */
    private fun handleShare(intent: android.content.Intent?) {
        if (intent?.action != android.content.Intent.ACTION_SEND) return
        val text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT) ?: return
        intent.removeExtra(android.content.Intent.EXTRA_TEXT)
        runCatching {
            androidx.lifecycle.ViewModelProvider(this)[com.volna.music.ui.MainVm::class.java].onShared(text)
        }
    }

    override fun onResume() {
        super.onResume()
        if (uiStarted) runCatching {
            val vm = androidx.lifecycle.ViewModelProvider(this)[com.volna.music.ui.MainVm::class.java]
            vm.refreshBatteryHint()
            vm.checkUpdateOnResume()
        }
    }
}
