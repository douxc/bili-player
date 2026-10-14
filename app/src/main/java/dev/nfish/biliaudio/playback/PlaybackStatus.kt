package dev.nfish.biliaudio.playback

import kotlinx.coroutines.flow.MutableStateFlow

/** Same-process transient status; the actual playback state belongs to MediaSession. */
object PlaybackStatus {
    val error = MutableStateFlow<String?>(null)
    val resolving = MutableStateFlow(false)
    val volume = MutableStateFlow(MusicVolume())
    val parts = MutableStateFlow(PartPlaybackState())
}
