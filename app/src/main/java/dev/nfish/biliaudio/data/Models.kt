package dev.nfish.biliaudio.data

data class Video(val bvid: String, val title: String, val author: String, val cover: String, val duration: String)
data class VideoPart(val cid: Long, val number: Int, val title: String, val durationSeconds: Long)
data class VideoDetail(val video: Video, val parts: List<VideoPart>)
data class SearchPage(val videos: List<Video>, val page: Int, val hasMore: Boolean)
data class AudioStream(val urls: List<String>, val mimeType: String)
data class QrSession(val key: String, val url: String)
data class QrStatus(val code: Int, val message: String)

fun formatTime(milliseconds: Long): String {
    val seconds = (milliseconds.coerceAtLeast(0) / 1000)
    return if (seconds >= 3600) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

object BvidInput {
    private val pattern = Regex("(?<![A-Za-z0-9])BV[1-9A-HJ-NP-Za-km-z]{10}(?![A-Za-z0-9])")
    fun extract(text: String): String? = pattern.find(text)?.value
}

class BiliException(val code: Int, message: String) : java.io.IOException(message)
