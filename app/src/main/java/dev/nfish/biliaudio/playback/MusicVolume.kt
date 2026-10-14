package dev.nfish.biliaudio.playback

import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The requested gain belongs to the service, independently of device volume. */
data class MusicVolume(val decibels: Float = 0f, val muted: Boolean = false) {
    init { require(decibels.isFinite() && decibels in MIN_DB..0f) }
    val gain: Float get() = if (muted) 0f else 10.0.pow(decibels / 20.0).toFloat()
    fun adjusted(value: Float): MusicVolume {
        require(value.isFinite())
        return MusicVolume(value.coerceIn(MIN_DB, 0f), muted = false)
    }
    fun toggleMute(): MusicVolume = copy(muted = !muted)

    companion object {
        const val MIN_DB = -60f
        fun restored(decibels: Float, muted: Boolean): MusicVolume =
            MusicVolume(if (decibels.isFinite()) decibels.coerceIn(MIN_DB, 0f) else 0f, muted)
    }
}

/** Retarget from the actual gain so rapid dragging never resumes an older fade. */
class VolumeFader(
    private val scope: CoroutineScope,
    private val readGain: () -> Float,
    private val writeGain: (Float) -> Unit
) {
    private var job: Job? = null
    fun fadeTo(target: Float) {
        require(target.isFinite() && target in 0f..1f)
        job?.cancel()
        val start = readGain()
        job = scope.launch {
            for (step in 1..10) {
                delay(10)
                writeGain(if (step == 10) target else start + (target - start) * (step / 10f))
            }
        }
    }
}
