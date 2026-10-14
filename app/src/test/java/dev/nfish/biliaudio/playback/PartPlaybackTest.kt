package dev.nfish.biliaudio.playback

import dev.nfish.biliaudio.data.AudioStream
import dev.nfish.biliaudio.data.Video
import dev.nfish.biliaudio.data.VideoDetail
import dev.nfish.biliaudio.data.VideoPart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PartPlaybackTest {
    private val detail = VideoDetail(Video("BV1xx411c7mD", "标题", "UP", "https://cover", "3:00"),
        (1..3).map { VideoPart(it.toLong(), it, "分 P $it", 60) })
    private val stream = AudioStream(listOf("https://audio", "https://backup"), "audio/mp4")

    private data class Start(val cid: Long, val candidate: Int, val position: Long, val playing: Boolean)
    private class Output : PartPlayer {
        val starts = mutableListOf<Start>()
        val selected = mutableListOf<Long>()
        var replays = 0
        var finished = 0
        var clears = 0
        var failures = 0
        override fun select(detail: VideoDetail, index: Int, playWhenReady: Boolean) { selected += detail.parts[index].cid }
        override fun start(detail: VideoDetail, index: Int, stream: AudioStream, candidate: Int, position: Long, playWhenReady: Boolean) {
            starts += Start(detail.parts[index].cid, candidate, position, playWhenReady)
        }
        override fun replay() { replays++ }
        override fun finish() { finished++ }
        override fun stop(clear: Boolean) { if (clear) clears++ else failures++ }
    }
    private fun engine(scope: CoroutineScope, output: Output,
        resolver: suspend (VideoDetail, VideoPart) -> AudioStream = { _, _ -> stream }) =
        PartPlayback(scope, resolver, output)

    @Test fun selectedPartResolvesLazilyAndContinuesInOrderToLast() = runTest {
        val output = Output(); val requested = mutableListOf<Long>()
        val player = engine(this, output) { _, part -> requested += part.cid; stream }
        assertTrue(player.play(detail, 2))
        advanceUntilIdle()
        assertEquals(listOf(2L), requested)
        player.ended(repeatOne = false); advanceUntilIdle()
        assertEquals(listOf(2L, 3L), requested)
        player.ended(repeatOne = false); advanceUntilIdle()
        assertEquals(listOf(2L, 3L), requested)
        assertEquals(1, output.finished)
        assertFalse(player.state.value.playWhenReady)
        assertNull(player.state.value.error)
    }

    @Test fun manualSwitchWhilePausedStartsTargetAtZero() = runTest {
        val output = Output(); val player = engine(this, output)
        player.play(detail, 2); advanceUntilIdle()
        player.recordPlayIntent(false)
        assertTrue(player.next()); advanceUntilIdle()
        assertEquals(Start(3, 0, 0, true), output.starts.last())
        player.recordPlayIntent(false)
        assertTrue(player.previous()); advanceUntilIdle()
        assertEquals(Start(2, 0, 0, true), output.starts.last())
        assertTrue(player.select(1)); advanceUntilIdle()
        assertEquals(Start(1, 0, 0, true), output.starts.last())
    }

    @Test fun boundariesDisableNavigationWithoutWrapping() = runTest {
        val output = Output(); val player = engine(this, output)
        player.play(detail, 1); advanceUntilIdle()
        assertFalse(player.state.value.hasPrevious)
        assertFalse(player.previous())
        assertTrue(player.state.value.hasNext)
        player.select(3); advanceUntilIdle()
        assertFalse(player.state.value.hasNext)
        assertFalse(player.next())
        assertFalse(player.select(999))
        assertEquals(3L, player.state.value.part?.cid)
    }

    @Test fun singlePartRepeatBlocksNormalAdvanceButNotManualSwitch() = runTest {
        val output = Output(); val player = engine(this, output)
        player.play(detail, 1); advanceUntilIdle()
        repeat(3) { player.ended(repeatOne = true) }
        assertEquals(3, output.replays)
        assertEquals(listOf(1L), output.starts.map { it.cid })
        player.next(); advanceUntilIdle()
        player.ended(repeatOne = true)
        assertEquals(2L, player.state.value.part?.cid)
        assertEquals(4, output.replays)
    }

    @Test fun fatalPlaybackFailureSkipsThenRepeatsSuccessfulPart() = runTest {
        val output = Output(); val player = engine(this, output)
        player.play(detail, 1); advanceUntilIdle()
        player.failed(player.state.value.mediaId, "解码失败", recoverable = false, position = 30000)
        advanceUntilIdle()
        assertEquals(Start(2, 0, 0, true), output.starts.last())
        player.ended(repeatOne = true)
        assertEquals(1, output.replays)
        assertEquals(2L, player.state.value.part?.cid)
    }

    @Test fun consecutiveResolutionFailuresStopAtLastWithoutWrapping() = runTest {
        val output = Output(); val attempts = mutableListOf<Long>()
        val player = engine(this, output) { _, part -> attempts += part.cid; error("无权限") }
        player.play(detail, 1); advanceUntilIdle()
        assertEquals(listOf(1L, 2L, 3L), attempts)
        assertEquals(3L, player.state.value.part?.cid)
        assertFalse(player.state.value.loading)
        assertFalse(player.state.value.playWhenReady)
        assertTrue(player.state.value.error.orEmpty().contains("最后一 P"))
        assertEquals(1, output.failures)
        player.ended(repeatOne = true); advanceUntilIdle()
        assertEquals(listOf(1L, 2L, 3L), attempts)
        assertTrue(output.starts.isEmpty())
    }

    @Test fun badPartsAreSkippedUntilAPlayablePart() = runTest {
        val output = Output(); val player = engine(this, output) { _, part ->
            if (part.cid < 3) error("音源不可用") else stream
        }
        player.play(detail, 1); advanceUntilIdle()
        assertEquals(listOf(1L, 2L, 3L), output.selected)
        assertEquals(listOf(Start(3, 0, 0, true)), output.starts)
        assertNull(player.state.value.error)
    }

    @Test fun networkRetriesAreBoundedPerPartAndKeepPosition() = runTest {
        val output = Output(); val player = engine(this, output)
        player.play(detail, 1); advanceUntilIdle()
        repeat(3) {
            player.failed(player.state.value.mediaId, "网络失败", recoverable = true, position = 42000)
            advanceUntilIdle()
        }
        assertEquals(listOf(Start(1, 0, 0, true), Start(1, 1, 42000, true),
            Start(1, 2, 42000, true), Start(2, 0, 0, true)), output.starts)
        repeat(3) {
            player.failed(player.state.value.mediaId, "网络失败", recoverable = true, position = 1000)
            advanceUntilIdle()
        }
        assertEquals(listOf(1L, 1L, 1L, 2L, 2L, 2L, 3L), output.starts.map { it.cid })
    }

    @Test fun refreshResolutionFailureSkipsAndResetsPosition() = runTest {
        val output = Output(); var firstAttempts = 0
        val player = engine(this, output) { _, part ->
            if (part.cid == 1L && firstAttempts++ > 0) error("地址失效") else stream
        }
        player.play(detail, 1); advanceUntilIdle()
        player.failed(player.state.value.mediaId, "HTTP 403", true, 30000); advanceUntilIdle()
        assertEquals(Start(2, 0, 0, true), output.starts.last())
    }

    @Test fun lateOldResolutionCannotOverwriteNewSelection() = runTest {
        val output = Output()
        val player = engine(this, output) { _, part ->
            if (part.cid == 1L) withContext(NonCancellable) { delay(100) }
            stream
        }
        player.play(detail, 1); runCurrent()
        player.select(2); advanceUntilIdle()
        assertEquals(listOf(2L), output.starts.map { it.cid })
        assertEquals(2L, player.state.value.part?.cid)
    }

    @Test fun stopDuringResolutionPreventsStartOrSkip() = runTest {
        val output = Output(); val player = engine(this, output) { _, _ -> delay(100); error("网络失败") }
        player.play(detail, 1); runCurrent()
        player.stop(); advanceUntilIdle()
        assertEquals(PartPlaybackState(), player.state.value)
        assertTrue(output.starts.isEmpty())
        assertEquals(listOf(1L), output.selected)
        assertEquals(1, output.clears)
    }

    @Test fun pauseDuringResolutionDoesNotResumeUnexpectedly() = runTest {
        val output = Output(); val player = engine(this, output) { _, _ -> delay(100); stream }
        player.play(detail, 1); runCurrent()
        player.recordPlayIntent(false); advanceUntilIdle()
        assertEquals(Start(1, 0, 0, false), output.starts.single())
    }

    @Test fun lateNonCancellableResultAfterStopCannotRestartServicePlayback() = runTest {
        val output = Output(); val player = engine(this, output) { _, _ ->
            withContext(NonCancellable) { delay(100) }
            stream
        }
        player.play(detail, 1); runCurrent()
        player.stop(); advanceUntilIdle()
        assertEquals(PartPlaybackState(), player.state.value)
        assertTrue(output.starts.isEmpty())
        assertEquals(1, output.clears)
    }

    @Test fun oldPlaybackErrorCannotSkipNewPart() = runTest {
        val output = Output(); val player = engine(this, output)
        player.play(detail, 1); advanceUntilIdle()
        val oldId = player.state.value.mediaId
        player.next(); advanceUntilIdle()
        player.failed(oldId, "旧事件", false, 0); advanceUntilIdle()
        assertEquals(2L, player.state.value.part?.cid)
        assertEquals(listOf(1L, 2L), output.starts.map { it.cid })
    }

    @Test fun retryAfterAllFailuresStartsLastPartWhenItBecomesAvailable() = runTest {
        val output = Output(); var available = false
        val player = engine(this, output) { _, _ -> if (available) stream else error("网络失败") }
        player.play(detail, 2); advanceUntilIdle()
        available = true
        assertTrue(player.retry()); advanceUntilIdle()
        assertEquals(Start(3, 0, 0, true), output.starts.single())
        assertNull(player.state.value.error)
    }

    @Test fun emptyAudioUrlsAreSkippedInsteadOfCrashing() = runTest {
        val output = Output(); val player = engine(this, output) { _, part ->
            if (part.cid == 1L) AudioStream(emptyList(), "audio/mp4") else stream
        }
        player.play(detail, 1); advanceUntilIdle()
        assertEquals(2L, output.starts.single().cid)
    }

    @Test fun replacingVideoNeverContinuesThePreviousVideo() = runTest {
        val output = Output(); val player = engine(this, output)
        player.play(detail, 1); advanceUntilIdle()
        val other = detail.copy(video = detail.video.copy(bvid = "BVdifferent"), parts = listOf(detail.parts.last()))
        player.play(other, 3); advanceUntilIdle()
        player.ended(repeatOne = false); advanceUntilIdle()
        assertEquals(other, player.state.value.detail)
        assertEquals(listOf(1L, 3L), output.starts.map { it.cid })
        assertEquals(1, output.finished)
    }
    @Test fun historyResumesSelectedPartAndFollowingPartStartsAtZero() = runTest {
        val output = Output(); val player = engine(this, output)
        player.play(detail, 2, position = 42000); advanceUntilIdle()
        assertEquals(Start(2, 0, 42000, true), output.starts.last())
        player.ended(repeatOne = false); advanceUntilIdle()
        assertEquals(Start(3, 0, 0, true), output.starts.last())
    }

    @Test fun failedHistoryPartDoesNotTransferItsPositionToNextPart() = runTest {
        val output = Output(); val player = engine(this, output) { _, part ->
            if (part.cid == 2L) error("不可用") else stream
        }
        player.play(detail, 2, position = 42000); advanceUntilIdle()
        assertEquals(Start(3, 0, 0, true), output.starts.single())
    }

}
