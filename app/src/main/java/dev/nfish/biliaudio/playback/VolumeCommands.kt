package dev.nfish.biliaudio.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand

object VolumeCommands {
    val set = SessionCommand("dev.nfish.biliaudio.SET_MUSIC_VOLUME", Bundle.EMPTY)
    val toggleMute = SessionCommand("dev.nfish.biliaudio.TOGGLE_MUSIC_MUTE", Bundle.EMPTY)
    const val DECIBELS = "decibels"
}
