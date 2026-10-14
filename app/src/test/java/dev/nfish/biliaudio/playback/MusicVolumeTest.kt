package dev.nfish.biliaudio.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MusicVolumeTest {
    @Test fun quietListeningRetainsNonzeroGainBelowSystemMinimum() {
        assertEquals(1f, MusicVolume().gain, 0f)
        assertEquals(0.1f, MusicVolume(-20f).gain, 0.000001f)
        assertEquals(0.001f, MusicVolume(-60f).gain, 0.000001f)
    }

    @Test fun muteRestoresTheChosenQuietLevel() {
        val quiet = MusicVolume(-32.5f)
        val muted = quiet.toggleMute()
        assertEquals(0f, muted.gain, 0f)
        assertEquals(-32.5f, muted.decibels, 0f)
        assertEquals(quiet, muted.toggleMute())
    }

    @Test fun draggingWhileMutedUnmutesWithoutAmplification() {
        assertEquals(MusicVolume(-60f), MusicVolume(-20f, true).adjusted(-100f))
        assertEquals(MusicVolume(0f), MusicVolume(-20f, true).adjusted(12f))
        assertThrows(IllegalArgumentException::class.java) { MusicVolume().adjusted(Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { MusicVolume().adjusted(Float.POSITIVE_INFINITY) }
    }

    @Test fun restoredSettingsKeepMuteAndSanitizeInvalidGain() {
        assertEquals(MusicVolume(-27.3f, true), MusicVolume.restored(-27.3f, true))
        assertEquals(MusicVolume(-60f), MusicVolume.restored(-200f, false))
        assertEquals(MusicVolume(0f, true), MusicVolume.restored(Float.NaN, true))
    }

    @Test fun attenuationFadesAndFinishesAtRequestedGain() = runTest {
        var actual = 1f
        val writes = mutableListOf<Float>()
        val fader = VolumeFader(this, { actual }, { actual = it; writes += it })
        fader.fadeTo(0.01f)
        runCurrent()
        assertEquals(1f, actual, 0f)
        advanceTimeBy(50); runCurrent()
        assertTrue(actual in 0.01f..1f && actual != 0.01f)
        advanceUntilIdle()
        assertEquals(0.01f, actual, 0f)
        assertEquals(10, writes.size)
        assertTrue(writes.zipWithNext().all { (a, b) -> b <= a })
    }

    @Test fun rapidRetargetCancelsOldFadeAndStartsFromActualGain() = runTest {
        var actual = 1f
        val writes = mutableListOf<Float>()
        val fader = VolumeFader(this, { actual }, { actual = it; writes += it })
        fader.fadeTo(0.01f)
        runCurrent(); advanceTimeBy(40); runCurrent()
        val intermediate = actual
        writes.clear()
        fader.fadeTo(0.9f)
        advanceUntilIdle()
        assertEquals(intermediate + (0.9f - intermediate) / 10, writes.first(), 0.000001f)
        assertTrue(writes.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(10, writes.size)
        assertEquals(0.9f, actual, 0f)
    }

    @Test fun muteFadeEndsInExactSilence() = runTest {
        var actual = 0.02f
        VolumeFader(this, { actual }, { actual = it }).fadeTo(0f)
        advanceUntilIdle()
        assertEquals(0f, actual, 0f)
    }
}
