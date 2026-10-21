package dev.nfish.biliaudio.playback

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.nfish.biliaudio.data.AudioStream
import dev.nfish.biliaudio.data.Video
import dev.nfish.biliaudio.data.VideoDetail
import dev.nfish.biliaudio.data.VideoPart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@UnstableApi
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PartSessionPlayerTest {
    private val detail = VideoDetail(Video("BV1xx411c7mD", "音乐", "UP", "https://cover", "3:00"),
        (1..3).map { VideoPart(it.toLong(), it, "P$it", 60) })
    private val stream = AudioStream(listOf("https://audio"), "audio/mp4")

    private class AudioPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        val seeks = mutableListOf<Long>()
        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAll(
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM).build())
            .setPlaylist(listOf(MediaItemData.Builder("one-audio-item")
                .setMediaItem(MediaItem.Builder().setMediaId("audio").build())
                .setDurationUs(60000000).setIsSeekable(true).build()))
            .setCurrentMediaItemIndex(0).setContentPositionMs(42000)
            .setPlaybackState(Player.STATE_READY).setRepeatMode(Player.REPEAT_MODE_ONE).build()

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            seeks += positionMs
            return Futures.immediateVoidFuture()
        }
    }

    private class Output : PartPlayer {
        var cid = 0L
        var position = -1L
        var playing = false
        override fun select(detail: VideoDetail, index: Int, playWhenReady: Boolean) = Unit
        override fun start(detail: VideoDetail, index: Int, stream: AudioStream, candidate: Int,
            position: Long, playWhenReady: Boolean) {
            cid = detail.parts[index].cid; this.position = position; playing = playWhenReady
        }
        override fun stop(clear: Boolean) = Unit
        override fun replay() = Unit
        override fun finish() = Unit
    }

    @Test fun systemNextSkipsPartsEvenWithASingleAudioItemAndRepeatOne() = runTest {
        val output = Output()
        val parts = PartPlayback(this, { _, _ -> stream }, output)
        parts.play(detail, 1); advanceUntilIdle()
        val audio = AudioPlayer()
        val session = PartSessionPlayer(audio, parts)
        assertTrue(session.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertFalse(session.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
        // Neither API may rely on ExoPlayer having a queued next audio item.
        session.seekToNext(); advanceUntilIdle()
        assertEquals(2L, output.cid)
        session.seekToNextMediaItem(); advanceUntilIdle()
        assertEquals(3L, output.cid)
        assertFalse(session.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        session.seekToNext(); advanceUntilIdle()
        assertEquals(3L, output.cid)
        assertTrue(audio.seeks.isEmpty())
    }

    @Test fun systemPreviousSwitchesWhilePausedInsteadOfRestartingCurrentAudio() = runTest {
        val output = Output()
        val parts = PartPlayback(this, { _, _ -> stream }, output)
        parts.play(detail, 3); advanceUntilIdle(); parts.recordPlayIntent(false)
        val audio = AudioPlayer()
        val session = PartSessionPlayer(audio, parts)
        session.seekToPrevious(); advanceUntilIdle()
        assertEquals(2L, output.cid)
        assertEquals(0L, output.position)
        assertTrue(output.playing)
        session.seekToPreviousMediaItem(); advanceUntilIdle()
        assertEquals(1L, output.cid)
        assertFalse(session.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
        assertTrue(audio.seeks.isEmpty())
    }

    @Test fun buttonsNotifyControllersAfterAppSelectionAndDisappearAfterStop() = runTest {
        val parts = PartPlayback(this, { _, _ -> stream }, Output())
        val session = PartSessionPlayer(AudioPlayer(), parts)
        var updates = 0
        session.addListener(object : Player.Listener {
            override fun onAvailableCommandsChanged(commands: Player.Commands) { updates++ }
        })
        parts.play(detail, 1); advanceUntilIdle(); session.refreshCommands()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(session.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        parts.select(3); advanceUntilIdle(); session.refreshCommands()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(session.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(session.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
        parts.stop(); session.refreshCommands()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(session.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
        assertFalse(session.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(updates >= 3)
    }

    @Test fun singlePartHasNoSkipButtonsAndProgressSeekingStillReachesAudioPlayer() = runTest {
        val parts = PartPlayback(this, { _, _ -> stream }, Output())
        parts.play(detail.copy(parts = detail.parts.take(1)), 1); advanceUntilIdle()
        val audio = AudioPlayer()
        val session = PartSessionPlayer(audio, parts)
        assertFalse(session.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertFalse(session.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
        session.seekTo(15000)
        assertEquals(listOf(15000L), audio.seeks)
    }
}
