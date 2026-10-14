package dev.nfish.biliaudio

import android.app.Application
import dev.nfish.biliaudio.data.BiliRepository
import dev.nfish.biliaudio.data.SessionStore
import dev.nfish.biliaudio.data.PlaybackHistory

class BiliAudioApp : Application() {
    val sessionStore by lazy { SessionStore(this) }
    val repository by lazy { BiliRepository(sessionStore) }
    val history by lazy {
        val preferences = getSharedPreferences("playback_history", MODE_PRIVATE)
        PlaybackHistory({ preferences.getString("recent", "").orEmpty() },
            { preferences.edit().putString("recent", it).apply() })
    }
}
