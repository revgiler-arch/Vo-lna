package com.volna.music.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * «Подслушивает» звук, который плеер отдаёт в динамики (без разрешения на микрофон),
 * и ~40 раз в секунду меряет громкость в [BeatMeter.BANDS] полосах частот — для эквалайзера.
 * Звук проходит насквозь без изменений. Пока тема «RGB — под музыку» выключена, анализа нет.
 */
@OptIn(UnstableApi::class)
class BeatTap : BaseAudioProcessor() {

    private var channels = 2
    private var float = false
    private var window = 1024   // кадров на одно измерение (~25 мс)

    private val n = BeatMeter.BANDS
    // полосовые фильтры (RBJ band-pass, 0 дБ в центре): у всех общий вход x1/x2
    private val b0 = FloatArray(n)
    private val a1 = FloatArray(n)
    private val a2 = FloatArray(n)
    private val y1 = FloatArray(n)
    private val y2 = FloatArray(n)
    private val acc = FloatArray(n)
    private var x1 = 0f
    private var x2 = 0f
    private var count = 0

    override fun onConfigure(format: AudioFormat): AudioFormat {
        if (format.encoding != C.ENCODING_PCM_16BIT && format.encoding != C.ENCODING_PCM_FLOAT) return AudioFormat.NOT_SET
        channels = format.channelCount.coerceAtLeast(1)
        float = format.encoding == C.ENCODING_PCM_FLOAT
        val fs = format.sampleRate.toDouble()
        window = (format.sampleRate / 40).coerceAtLeast(256)
        val q = 1.6
        for (k in 0 until n) {
            val f = BeatMeter.centers[k].coerceAtMost(fs * 0.45)
            val w = 2 * PI * f / fs
            val alpha = sin(w) / (2 * q)
            val a0 = 1 + alpha
            b0[k] = (alpha / a0).toFloat()
            a1[k] = (-2 * cos(w) / a0).toFloat()
            a2[k] = ((1 - alpha) / a0).toFloat()
        }
        clear()
        return format // формат не меняем — только смотрим
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        if (BeatMeter.enabled) analyze(inputBuffer.duplicate().order(ByteOrder.nativeOrder()))
        val out = replaceOutputBuffer(size)
        out.put(inputBuffer)
        out.flip()
    }

    private fun analyze(b: ByteBuffer) {
        val bytesPerFrame = channels * (if (float) 4 else 2)
        val frames = b.remaining() / bytesPerFrame
        for (i in 0 until frames) {
            var x = 0f
            for (c in 0 until channels) {
                x += if (float) b.getFloat() else b.getShort() / 32768f
            }
            x /= channels
            for (k in 0 until n) {
                // b1 = 0, b2 = -b0 у полосового фильтра
                val y = b0[k] * (x - x2) - a1[k] * y1[k] - a2[k] * y2[k]
                y2[k] = y1[k]; y1[k] = y
                acc[k] += y * y
            }
            x2 = x1; x1 = x
            if (++count >= window) {
                val out = FloatArray(n) { sqrt(acc[it] / count) }
                BeatMeter.push(out)
                acc.fill(0f); count = 0
            }
        }
    }

    private fun clear() {
        x1 = 0f; x2 = 0f; y1.fill(0f); y2.fill(0f); acc.fill(0f); count = 0
        BeatMeter.clear()
    }

    override fun onFlush() = clear() // перемотка / следующий трек
    override fun onReset() = clear()
}

/**
 * Связь между звуком (поток плеера) и экраном. Измерения откладываются на время,
 * пока звук сидит в буфере вывода, — чтобы столбики двигались вместе со звуком, а не раньше.
 */
object BeatMeter {
    const val BANDS = 12

    /** Центры полос, Гц: от суббаса до верхов, равномерно по октавам. */
    val centers = DoubleArray(BANDS) { 45.0 * Math.pow(12000.0 / 45.0, it / (BANDS - 1.0)) }

    @Volatile var enabled = false

    /** Сколько звук идёт от обработки до динамика. Через Bluetooth — дольше. */
    @Volatile var delayMs = 260L

    class Sample(val bands: FloatArray, val level: Float, val beats: Int)

    private const val CAP = 128
    private val due = LongArray(CAP)
    private val vals = Array(CAP) { FloatArray(BANDS) }
    private val lvl = FloatArray(CAP)
    private val hit = BooleanArray(CAP)
    private var head = 0
    private var tail = 0

    // автоподстройка: у каждой полосы свой «потолок», медленно опускается — так столбики
    // одинаково живые и в тихой, и в громкой песне
    private val ceil = FloatArray(BANDS) { 1e-3f }
    private var bassAvg = 0f
    private var sinceBeat = 100
    private val last = FloatArray(BANDS)
    private var lastLevel = 0f
    private var lastDue = 0L

    @Synchronized fun push(raw: FloatArray) {
        val slot = vals[head]
        var sum = 0f
        for (k in 0 until BANDS) {
            val r = raw[k]
            ceil[k] = maxOf(r, ceil[k] * 0.9965f, 2e-4f) // ~7 с, чтобы опуститься вдвое
            // в децибелах от потолка: 0 дБ = полный столбик, −36 дБ = пусто
            val db = 20f * log10((r / ceil[k]).coerceAtLeast(1e-6f))
            slot[k] = ((db + 36f) / 36f).coerceIn(0f, 1f)
            sum += slot[k]
        }
        val bass = (raw[0] + raw[1] + raw[2]) / 3f
        bassAvg += (bass - bassAvg) * 0.05f
        sinceBeat++
        val beat = bass > bassAvg * 1.5f && bass > 0.01f && sinceBeat >= 9 // не чаще ~4 раз/с
        if (beat) sinceBeat = 0

        lastDue = System.nanoTime() + delayMs * 1_000_000
        due[head] = lastDue
        lvl[head] = sum / BANDS
        hit[head] = beat
        head = (head + 1) % CAP
        if (head == tail) tail = (tail + 1) % CAP // переполнение — выкидываем старое
    }

    /** Всё, что «прозвучало» к моменту [now]: последние уровни полос, общая громкость, число ударов. */
    @Synchronized fun poll(now: Long): Sample {
        var beats = 0
        while (tail != head && due[tail] <= now) {
            System.arraycopy(vals[tail], 0, last, 0, BANDS)
            lastLevel = lvl[tail]
            if (hit[tail]) beats++
            tail = (tail + 1) % CAP
        }
        // давно ничего не играло (пауза) — гасим
        if (tail == head && now - lastDue > 300_000_000) { last.fill(0f); lastLevel = 0f }
        return Sample(last.copyOf(), lastLevel, beats)
    }

    @Synchronized fun clear() { tail = head; last.fill(0f); lastLevel = 0f }
}
