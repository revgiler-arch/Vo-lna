package com.volna.music.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Оформления приложения. Во всех — белые буквы на насыщенном фоне (лучше читается, в том числе в машине). */
enum class AppTheme(val title: String) {
    DARK("Тёмная"),
    BLUE("Синяя"),
    EMERALD("Изумрудная"),
}

/** Цвет иконок и текста на белой панели управления плеером. */
val LocalPanelInk = staticCompositionLocalOf { Color(0xFF101014) }

object ThemePrefs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)
    fun load(ctx: Context): AppTheme =
        runCatching { AppTheme.valueOf(prefs(ctx).getString("theme", null) ?: "DARK") }.getOrDefault(AppTheme.DARK)
    fun save(ctx: Context, t: AppTheme) { prefs(ctx).edit().putString("theme", t.name).apply() }
}

private val White = Color.White

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8C9EFF), onPrimary = Color(0xFF0A0A20),
    primaryContainer = Color(0xFF2A2F55), onPrimaryContainer = White,
    secondary = Color(0xFF4DD0E1),
    secondaryContainer = Color(0xFF2E2E3A), onSecondaryContainer = White,
    background = Color(0xFF0E0E11), onBackground = White,
    surface = Color(0xFF0E0E11), onSurface = White,
    surfaceVariant = Color(0xFF26262D), onSurfaceVariant = Color(0xFFB9B9C3),
    surfaceContainer = Color(0xFF18181D), surfaceContainerHigh = Color(0xFF1E1E24),
    surfaceContainerHighest = Color(0xFF26262D),
    outline = Color(0xFF55555F),
)

// Цвета иконки приложения: синий 3D5AFE → голубой 00B8D4
private val BlueScheme = darkColorScheme(
    primary = Color(0xFF7FE3FF), onPrimary = Color(0xFF0A1A5C),
    primaryContainer = Color(0xFF3550D8), onPrimaryContainer = White,
    secondary = Color(0xFF7FE3FF),
    secondaryContainer = Color(0xFF3A57E0), onSecondaryContainer = White,
    background = Color(0xFF1B34B8), onBackground = White,
    surface = Color(0xFF1B34B8), onSurface = White,
    surfaceVariant = Color(0xFF2E4AD0), onSurfaceVariant = Color(0xFFD5DDFF),
    surfaceContainer = Color(0xFF162B9E), surfaceContainerHigh = Color(0xFF14278F),
    surfaceContainerHighest = Color(0xFF2E4AD0),
    outline = Color(0xFF8FA2F0),
)

private val EmeraldScheme = darkColorScheme(
    primary = Color(0xFF7CF5C4), onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF17795C), onPrimaryContainer = White,
    secondary = Color(0xFFB8F5DC),
    secondaryContainer = Color(0xFF1B7E60), onSecondaryContainer = White,
    background = Color(0xFF0C5C46), onBackground = White,
    surface = Color(0xFF0C5C46), onSurface = White,
    surfaceVariant = Color(0xFF167358), onSurfaceVariant = Color(0xFFCFF2E4),
    surfaceContainer = Color(0xFF094E3B), surfaceContainerHigh = Color(0xFF084533),
    surfaceContainerHighest = Color(0xFF167358),
    outline = Color(0xFF7FC9AE),
)

@Composable
fun VolnaTheme(theme: AppTheme, content: @Composable () -> Unit) {
    val (scheme, ink) = when (theme) {
        AppTheme.DARK -> DarkScheme to Color(0xFF101014)
        AppTheme.BLUE -> BlueScheme to Color(0xFF1B34B8)
        AppTheme.EMERALD -> EmeraldScheme to Color(0xFF0C5C46)
    }
    CompositionLocalProvider(LocalPanelInk provides ink) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
