package dev.nfish.biliaudio.playback

import dev.nfish.biliaudio.data.Video
import dev.nfish.biliaudio.data.VideoDetail
import dev.nfish.biliaudio.data.VideoPart
import org.junit.Assert.*
import org.junit.Test

class VideoDetailCodecTest {
    private val detail = VideoDetail(Video("BV1xx411c7mD", "音乐 · 标题 \"现场\"", "UP", "https://cover", "1:00"),
        listOf(VideoPart(100000000001L, 1, "第一首\n现场", 60), VideoPart(100000000002L, 2, "第二首", 120)))

    @Test fun metadataRoundTripPreservesPartOrderLongCidsAndUnicode() {
        assertEquals(detail, VideoDetailCodec.decode(VideoDetailCodec.encode(detail)))
    }
    @Test fun invalidPartMetadataIsRejectedBeforeReplacingPlayback() {
        assertThrows(IllegalArgumentException::class.java) {
            VideoDetailCodec.decode(VideoDetailCodec.encode(detail.copy(parts = emptyList())))
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoDetailCodec.decode(VideoDetailCodec.encode(detail.copy(parts = listOf(detail.parts.first(), detail.parts.first()))))
        }
    }
}
