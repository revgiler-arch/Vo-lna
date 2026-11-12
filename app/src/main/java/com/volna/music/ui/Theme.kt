package com.volna.music.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** Оформления приложения. Во всех — белые буквы на насыщенном фоне (лучше читается, в том числе в машине). */
enum class AppTheme(val title: String) {
    DARK("Тёмная"),
    BLUE("Синяя"),
    EMERALD("Изумрудная"),
    RGB("RGB — переливается"),
    RGB_BEAT("RGB — под музыку"),
}

/**
 * Для темы RGB — текущий оттенок 0..360, плавно бегущий по кругу, как у RGB-ленты.
 * Фон читает его только при рисовании, поэтому перелив не пересобирает экран каждый кадр.
 */
val LocalRgbHue = staticCompositionLocalOf<State<Float>?> { null }

/** Для темы «под музыку» — вспышка на ударе 0..1 (быстро гаснет). */
val LocalRgbGlow = staticCompositionLocalOf<State<Float>?> { null }

/** Эквалайзер для темы «под музыку»: уровни полос и падающие пики, 0..1. [frame] растёт каждый кадр. */
class EqState {
    val bars = FloatArray(com.volna.music.player.BeatMeter.BANDS)
    val peaks = FloatArray(com.volna.music.player.BeatMeter.BANDS)
    val frame = androidx.compose.runtime.mutableIntStateOf(0)
}

val LocalEq = staticCompositionLocalOf<EqState?> { null }

/** Фон приложения: обычный цвет темы или переливающийся градиент RGB. */
fun Modifier.appBackground(): Modifier = composed {
    val hue = LocalRgbHue.current
    val glow = LocalRgbGlow.current
    val eq = LocalEq.current
    if (hue == null) {
        background(MaterialTheme.colorScheme.background)
    } else {
        graphicsLayer {}.drawBehind {
            val h = hue.value
            val g = (glow?.value ?: 0f) * (if (eq != null) 0.5f else 1f)
            // на ударе фон вспыхивает ярче, как LED-лента в режиме «музыка»
            drawRect(
                Brush.linearGradient(
                    listOf(
                        Color.hsv(h % 360f, 0.85f - 0.15f * g, (0.50f + 0.45f * g).coerceAtMost(1f)),
                        Color.hsv((h + 60f) % 360f, 0.85f, 0.38f + 0.35f * g),
                        Color.hsv((h + 120f) % 360f, 0.85f, 0.26f + 0.22f * g),
                    ),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                ),
            )
            if (eq != null) drawEq(eq, h)
        }
    }
}

/**
 * Эквалайзер у левого края: полосы растут вправо, бас внизу, верха сверху,
 * у каждой — свой цвет радуги и пиковая метка, которая медленно опадает.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEq(eq: EqState, hue: Float) {
    eq.frame.intValue // подписка: перерисовываться каждый кадр
    val n = eq.bars.size
    val maxW = minOf(size.width * 0.30f, 90.dp.toPx() * 2.2f)
    val top = size.height * 0.08f
    val bottom = size.height * 0.92f
    val rowH = (bottom - top) / n
    val barH = rowH * 0.62f
    val r = androidx.compose.ui.geometry.CornerRadius(barH / 2)
    val cap = 3.dp.toPx()
    for (k in 0 until n) {
        val y = bottom - (k + 1) * rowH + (rowH - barH) / 2
        val v = eq.bars[k]
        val c = Color.hsv((hue + k * (300f / n)) % 360f, 0.85f, 1f)
        // тусклая «дорожка» на всю длину — видно шкалу даже в тишине
        drawRoundRect(c.copy(alpha = 0.10f), Offset(0f, y), androidx.compose.ui.geometry.Size(maxW, barH), r)
        if (v > 0.01f) {
            val w = maxOf(barH, maxW * v)
            drawRoundRect(
                Brush.horizontalGradient(listOf(c.copy(alpha = 0.35f), c.copy(alpha = 0.9f)), 0f, w),
                Offset(0f, y), androidx.compose.ui.geometry.Size(w, barH), r,
            )
        }
        val p = eq.peaks[k]
        if (p > 0.02f) {
            drawRect(Color.White.copy(alpha = 0.85f), Offset(maxW * p, y), androidx.compose.ui.geometry.Size(cap, barH))
        }
    }
}

/** Отдельный слой с фоном: кладётся под содержимое, чтобы перелив перерисовывал только себя. */
@Composable
fun AppBackdrop(modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(modifier.appBackground())
}

/** Цвета элементов в теме RGB: подстраиваются под текущий оттенок (меняются шагами, это дёшево). */
private fun rgbScheme(h: Float) = darkColorScheme(
    primary = Color.hsv(h % 360f, 0.45f, 1f), onPrimary = Color(0xFF101014),
    primaryContainer = Color.hsv(h % 360f, 0.7f, 0.45f), onPrimaryContainer = Color.White,
    secondary = Color.hsv((h + 60f) % 360f, 0.45f, 1f),
    secondaryContainer = Color.hsv((h + 60f) % 360f, 0.7f, 0.4f), onSecondaryContainer = Color.White,
    background = Color.Transparent, onBackground = Color.White,
    surface = Color.Transparent, onSurface = Color.White,
    surfaceVariant = Color(0x33FFFFFF), onSurfaceVariant = Color(0xFFE6E6EE),
    surfaceContainer = Color(0xFF15151C), surfaceContainerHigh = Color(0xFF1C1C24),
    surfaceContainerHighest = Color(0xFF26262E),
    outline = Color(0xFFB0B0C0),
)

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
    if (theme == AppTheme.RGB_BEAT) {
        BeatTheme(content)
        return
    }
    if (theme == AppTheme.RGB) {
        val hue = rememberInfiniteTransition(label = "rgb").animateFloat(
            initialValue = 0f, targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 24_000, easing = LinearEasing)),
            label = "hue",
        )
        // кнопки и акценты меняют цвет шагами по 6°: глазу плавно, а экран пересобирается редко
        val step by remember { derivedStateOf { (hue.value / 6f).toInt() * 6f } }
        CompositionLocalProvider(LocalPanelInk provides Color(0xFF101014), LocalRgbHue provides hue) {
            MaterialTheme(colorScheme = rgbScheme(step), content = content)
        }
        return
    }
    val (scheme, ink) = when (theme) {
        AppTheme.DARK -> DarkScheme to Color(0xFF101014)
        AppTheme.BLUE -> BlueScheme to Color(0xFF1B34B8)
        AppTheme.EMERALD -> EmeraldScheme to Color(0xFF0C5C46)
        AppTheme.RGB, AppTheme.RGB_BEAT -> DarkScheme to Color(0xFF101014)
    }
    CompositionLocalProvider(LocalPanelInk provides ink) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/**
 * RGB под музыку. Каждый кадр берём из [com.volna.music.player.BeatMeter] громкость баса и удары:
 * удар — вспышка и рывок цвета вперёд, громко — перелив быстрее, тихо/пауза — спокойный перелив.
 * Перерисовывается только фон; кнопки меняют цвет редко и по медленному оттенку, чтобы не грузить экран.
 */
@Composable
private fun BeatTheme(content: @Composable () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val hue = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val glow = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val slowHue = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val eq = remember { EqState() }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        val meter = com.volna.music.player.BeatMeter
        meter.enabled = true
        try {
            var last = 0L
            var pulse = 0f
            var kick = 0f      // добавочная скорость цвета после удара, °/с
            var level = 0f
            var nextOutputCheck = 0L
            while (true) {
                androidx.compose.runtime.withFrameNanos { _ -> }
                val now = System.nanoTime()
                if (now >= nextOutputCheck) {
                    meter.delayMs = if (bluetoothOutput(ctx)) 420L else 260L
                    nextOutputCheck = now + 5_000_000_000L
                }
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                last = now

                val s = meter.poll(now)
                // столбики: вверх быстро, вниз плавно; пики держатся и медленно опадают
                for (k in s.bands.indices) {
                    val target = s.bands[k]
                    val cur = eq.bars[k]
                    eq.bars[k] = if (target > cur) cur + (target - cur) * (dt * 30f).coerceAtMost(1f)
                    else maxOf(target, cur - dt * 1.6f)
                    eq.peaks[k] = if (eq.bars[k] >= eq.peaks[k]) eq.bars[k] else maxOf(0f, eq.peaks[k] - dt * 0.35f)
                }
                eq.frame.intValue++
                level += (s.level - level) * (dt * 12f).coerceAtMost(1f)
                if (s.beats > 0) { pulse = 1f; kick = 120f }
                pulse = maxOf(pulse - dt * 3.2f, level * 0.35f)
                kick *= exp(-dt * 5f)

                hue.floatValue = (hue.floatValue + dt * (10f + 30f * level + kick)) % 360f
                slowHue.floatValue = (slowHue.floatValue + dt * 15f) % 360f
                glow.floatValue = pulse
            }
        } finally {
            meter.enabled = false
        }
    }

    // кнопки — по медленному оттенку и шагами по 10°: ~1,5 пересборки экрана в секунду
    val step by remember { derivedStateOf { (slowHue.floatValue / 10f).toInt() * 10f } }
    CompositionLocalProvider(
        LocalPanelInk provides Color(0xFF101014),
        LocalRgbHue provides hue,
        LocalRgbGlow provides glow,
        LocalEq provides eq,
    ) {
        MaterialTheme(colorScheme = rgbScheme(step), content = content)
    }
}

/** Звук идёт в Bluetooth (наушники, машина по BT) — там задержка больше. */
private fun bluetoothOutput(ctx: Context): Boolean = runCatching {
    val am = ctx.getSystemService(android.media.AudioManager::class.java) ?: return false
    am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any {
        it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
    }
}.getOrDefault(false)

private fun exp(x: Float): Float = kotlin.math.exp(x)
