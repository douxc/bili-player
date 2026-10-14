package dev.nfish.biliaudio.data

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray

class PlaybackHistoryTest {
    private fun entry(cid: Long) = HistoryEntry(
        Video("BV1xx411c7mD", "视频·音乐", "UP 主", "https://cover.example/a.jpg", "12:00"),
        VideoPart(cid, cid.toInt(), "分 P $cid", 120))

    @Test fun keepsOnlyTheThreeMostRecentParts() {
        val history = PlaybackHistory({ "" }, {})
        (1L..4L).forEach { history.record(entry(it)) }
        assertEquals(listOf(4L, 3L, 2L), history.entries.value.map { it.part.cid })
    }

    @Test fun replayMovesAnExistingPartToFrontWithoutDuplicates() {
        val history = PlaybackHistory({ "" }, {})
        (1L..3L).forEach { history.record(entry(it)) }
        history.record(entry(1))
        assertEquals(listOf(1L, 3L, 2L), history.entries.value.map { it.part.cid })
    }

    @Test fun theSameCidInDifferentVideosRemainsDistinct() {
        val history = PlaybackHistory({ "" }, {})
        history.record(entry(1))
        val second = entry(1).let { it.copy(video = it.video.copy(bvid = "BV1yy411c7mD")) }
        history.record(second)
        assertEquals(2, history.entries.value.size)
    }

    @Test fun persistedMetadataRestoresOrderAndLargeCid() {
        var saved = ""
        val history = PlaybackHistory({ saved }, { saved = it })
        history.record(entry(1))
        val large = entry(2).copy(part = VideoPart(9007199254740993L, 2, "音乐 🎵", 123))
        history.record(large)
        val restored = PlaybackHistory({ saved }, {})
        assertEquals(listOf(large, entry(1)), restored.entries.value)
        assertFalse(saved.contains("urls"))
        assertFalse(saved.contains("position"))
    }

    @Test fun corruptedStorageDoesNotPreventStartup() {
        assertTrue(PlaybackHistory({ "{broken" }, {}).entries.value.isEmpty())
        assertTrue(PlaybackHistory({ error("read failed") }, {}).entries.value.isEmpty())
    }

    @Test fun invalidRowsAreSkippedAndValidRowsAreDeduplicatedAndBounded() {
        val array = JSONArray(HistoryCodec.encode(listOf(entry(4), entry(3), entry(2))))
        array.put(array.getJSONObject(1))
        array.put(JSONArray(HistoryCodec.encode(listOf(entry(1)))).getJSONObject(0))
        array.getJSONObject(0).put("cid", -1)
        assertEquals(listOf(3L, 2L, 1L), HistoryCodec.decode(array.toString()).map { it.part.cid })
    }
}
