package dev.nfish.biliaudio.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand
import dev.nfish.biliaudio.data.Video
import dev.nfish.biliaudio.data.VideoDetail
import dev.nfish.biliaudio.data.VideoPart
import org.json.JSONArray
import org.json.JSONObject

object PartCommands {
    val play = SessionCommand("dev.nfish.biliaudio.PLAY_VIDEO_PARTS", Bundle.EMPTY)
    val select = SessionCommand("dev.nfish.biliaudio.SELECT_PART", Bundle.EMPTY)
    val previous = SessionCommand("dev.nfish.biliaudio.PREVIOUS_PART", Bundle.EMPTY)
    val next = SessionCommand("dev.nfish.biliaudio.NEXT_PART", Bundle.EMPTY)
    val retry = SessionCommand("dev.nfish.biliaudio.RETRY_PART", Bundle.EMPTY)
    const val DETAIL = "video_detail"
    const val CID = "cid"
    const val POSITION = "position_ms"
    const val RESUME = "resume_history"
}

/** Transfer metadata, never temporary CDN URLs or account credentials. */
object VideoDetailCodec {
    fun encode(detail: VideoDetail): String = JSONObject().apply {
        put("bvid", detail.video.bvid); put("title", detail.video.title)
        put("author", detail.video.author); put("cover", detail.video.cover)
        put("duration", detail.video.duration)
        put("parts", JSONArray().apply {
            detail.parts.forEach { part -> put(JSONObject().apply {
                put("cid", part.cid); put("number", part.number)
                put("title", part.title); put("duration", part.durationSeconds)
            }) }
        })
    }.toString()

    fun decode(value: String): VideoDetail {
        val obj = JSONObject(value)
        val array = obj.getJSONArray("parts")
        val parts = (0 until array.length()).map { index -> array.getJSONObject(index).let {
            VideoPart(it.getLong("cid"), it.getInt("number"), it.getString("title"), it.getLong("duration"))
        } }
        require(parts.isNotEmpty() && parts.all { it.cid > 0 && it.number > 0 && it.durationSeconds >= 0 })
        require(parts.map { it.cid }.distinct().size == parts.size)
        return VideoDetail(Video(obj.getString("bvid"), obj.getString("title"), obj.getString("author"),
            obj.getString("cover"), obj.getString("duration")), parts)
    }
}
