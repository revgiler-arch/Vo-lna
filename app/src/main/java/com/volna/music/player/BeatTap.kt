package com.volna.music.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * «Подслушивает» звук, который плеер отдаёт в динамики (без разрешения на микрофон),
 * и считает силу баса ~40 раз в секунду. Звук проходит насквозь без изменений.
 * Пока тема «RGB — под музыку» выключена, анализ не делается вовсе.
 */
@OptIn(UnstableApi::class)
class BeatTap : BaseAudioProcessor() {

    private var channels = 2
    private var float = false
    private var alpha = 0f      // коэффициент фильтра низких частот (~150 Гц)
    private var window = 1024   // кадров на одно измерение (~25 мс)

    private var lp = 0f
    private var sum = 0f
    private var count = 0

    override fun onConfigure(format: AudioFormat): AudioFormat {
        if (format.encoding != C.ENCODING_PCM_16BIT && format.encoding != C.ENCODING_PCM_FLOAT) return AudioFormat.NOT_SET
        channels = format.channelCount.coerceAtLeast(1)
        float = format.encoding == C.ENCODING_PCM_FLOAT
        alpha = 1f - exp(-2.0 * PI * 150.0 / format.sampleRate).toFloat()
        window = (format.sampleRate / 40).coerceAtLeast(256)
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
            var mono = 0f
            for (c in 0 until channels) {
                mono += if (float) b.getFloat() else b.getShort() / 32768f
            }
            mono /= channels
            lp += alpha * (mono - lp)
            sum += lp * lp
            if (++count >= window) {
                BeatMeter.push(sqrt(sum / count))
                sum = 0f; count = 0
            }
        }
    }

    private fun clear() { lp = 0f; sum = 0f; count = 0; BeatMeter.clear() }
    override fun onFlush() = clear() // перемотка / следующий трек
    override fun onReset() = clear()
}

/**
 * Связь между звуком (поток плеера) и фоном (экран). Измерения откладываются на время,
 * пока звук сидит в буфере вывода, — чтобы вспышка совпала с ударом, а не опережала его.
 */
object BeatMeter {
    @Volatile var enabled = false

    /** Сколько звук идёт от обработки до динамика. Через Bluetooth — дольше. */
    @Volatile var delayMs = 260L

    class Sample(val level: Float, val beats: Int)

    private const val CAP = 128
    private val due = LongArray(CAP)
    private val lvl = FloatArray(CAP)
    private val hit = BooleanArray(CAP)
    private var head = 0
    private var tail = 0

    private var avg = 0f
    private var sinceBeat = 100
    private var lastLevel = 0f
    private var lastDue = 0L

    @Synchronized fun push(bass: Float) {
        // средняя громкость баса за ~1 с — от неё и считаем «удар»
        avg += (bass - avg) * 0.04f
        sinceBeat++
        val beat = bass > avg * 1.45f && bass > 0.015f && sinceBeat >= 10 // не чаще ~4 раз/с
        if (beat) sinceBeat = 0
        val level = if (avg > 1e-4f) (bass / (avg * 2.2f)).coerceIn(0f, 1f) else 0f

        lastDue = System.nanoTime() + delayMs * 1_000_000
        due[head] = lastDue
        lvl[head] = level
        hit[head] = beat
        head = (head + 1) % CAP
        if (head == tail) tail = (tail + 1) % CAP // переполнение — выкидываем старое
    }

    /** Всё, что «прозвучало» к моменту [now]: последняя громкость и число ударов. */
    @Synchronized fun poll(now: Long): Sample {
        var beats = 0
        while (tail != head && due[tail] <= now) {
            lastLevel = lvl[tail]
            if (hit[tail]) beats++
            tail = (tail + 1) % CAP
        }
        // давно ничего не играло (пауза) — гасим
        if (tail == head && now - lastDue > 300_000_000) lastLevel = 0f
        return Sample(lastLevel, beats)
    }

    @Synchronized fun clear() { tail = head; lastLevel = 0f }
}
