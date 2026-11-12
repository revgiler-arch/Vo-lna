package com.volna.music.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Таймер сна: общий для экрана и фонового плеера (один процесс).
 * Сам выключает музыку [PlaybackService]; экран только ставит и показывает.
 */
object SleepTimer {
    /** Когда выключить (System.currentTimeMillis), или null. */
    private val _endAt = MutableStateFlow<Long?>(null)
    val endAt: StateFlow<Long?> = _endAt

    /** Выключить, когда доиграет текущий трек. */
    private val _endOfTrack = MutableStateFlow(false)
    val endOfTrack: StateFlow<Boolean> = _endOfTrack

    /** Сколько последних миллисекунд плавно убавляем громкость. */
    const val FADE_MS = 30_000L

    fun inMinutes(min: Int) {
        _endOfTrack.value = false
        _endAt.value = System.currentTimeMillis() + min * 60_000L
    }

    /** Выключить в hh:mm (если время уже прошло — завтра). */
    fun at(hour: Int, minute: Int) {
        val c = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= System.currentTimeMillis()) c.add(java.util.Calendar.DAY_OF_YEAR, 1)
        _endOfTrack.value = false
        _endAt.value = c.timeInMillis
    }

    fun afterThisTrack() {
        _endAt.value = null
        _endOfTrack.value = true
    }

    fun cancel() {
        _endAt.value = null
        _endOfTrack.value = false
    }

    val active: Boolean get() = _endAt.value != null || _endOfTrack.value
}
