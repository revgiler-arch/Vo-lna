package com.volna.music.data

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val artistUrl: String?,
    val thumb: String?,
    val durationSec: Long,
)

fun formatTime(seconds: Long): String {
    if (seconds <= 0) return "0:00"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
