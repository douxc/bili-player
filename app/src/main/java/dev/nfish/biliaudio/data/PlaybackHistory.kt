package dev.nfish.biliaudio.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(val video: Video, val part: VideoPart, val positionMs: Long = 0) {
    fun resumePosition(durationSeconds: Long = part.durationSeconds): Long =
        positionMs.coerceAtLeast(0).takeUnless { durationSeconds > 0 && it / 1000 >= durationSeconds } ?: 0
    val key: String get() = "${video.bvid}:${part.cid}"
}

/** Only the three most recently played video/part pairs, with progress, without stream URLs. */
class PlaybackHistory(read: () -> String, private val write: (String) -> Unit) {
    private val _entries = MutableStateFlow(HistoryCodec.decode(runCatching(read).getOrDefault("")))
    val entries = _entries.asStateFlow()

    @Synchronized
    fun updateProgress(key: String, positionMs: Long) {
        val next = _entries.value.map { if (it.key == key) it.copy(positionMs = positionMs.coerceAtLeast(0)) else it }
        if (next == _entries.value) return
        write(HistoryCodec.encode(next))
        _entries.value = next
    }

    @Synchronized
    fun record(entry: HistoryEntry) {
        val next = (listOf(entry) + _entries.value.filter { it.key != entry.key }).take(3)
        write(HistoryCodec.encode(next))
        _entries.value = next
    }
}

internal object HistoryCodec {
    fun encode(entries: List<HistoryEntry>): String = JSONArray().apply {
        entries.take(3).forEach { entry -> put(JSONObject().apply {
            val video = entry.video
            put("bvid", video.bvid); put("title", video.title); put("author", video.author)
            put("cover", video.cover); put("duration", video.duration)
            put("positionMs", entry.positionMs.coerceAtLeast(0))
            put("cid", entry.part.cid); put("number", entry.part.number)
            put("partTitle", entry.part.title); put("partDuration", entry.part.durationSeconds)
        }) }
    }.toString()

    fun decode(value: String): List<HistoryEntry> = runCatching {
        val array = JSONArray(value)
        (0 until array.length()).mapNotNull { index -> runCatching {
            val obj = array.getJSONObject(index)
            val video = Video(obj.getString("bvid"), obj.getString("title"), obj.getString("author"),
                obj.getString("cover"), obj.getString("duration"))
            val part = VideoPart(obj.getLong("cid"), obj.getInt("number"), obj.getString("partTitle"),
                obj.getLong("partDuration"))
            require(BvidInput.extract(video.bvid) == video.bvid && part.cid > 0 &&
                part.number > 0 && part.durationSeconds >= 0)
            HistoryEntry(video, part, obj.optLong("positionMs", 0).coerceAtLeast(0))
        }.getOrNull() }.distinctBy { it.key }.take(3)
    }.getOrDefault(emptyList())
}
