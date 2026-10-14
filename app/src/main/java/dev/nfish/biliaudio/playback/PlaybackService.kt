package dev.nfish.biliaudio.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.nfish.biliaudio.BiliAudioApp
import dev.nfish.biliaudio.MainActivity
import dev.nfish.biliaudio.data.AudioStream
import dev.nfish.biliaudio.data.VideoDetail
import dev.nfish.biliaudio.data.HistoryEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@UnstableApi
class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private lateinit var parts: PartPlayback
    private val repository get() = (application as BiliAudioApp).repository
    private val volumePreferences by lazy { getSharedPreferences("music_volume", MODE_PRIVATE) }
    private lateinit var volumeFader: VolumeFader
    private var volumeSaveJob: Job? = null
    private var recordedMediaId: String? = null

    override fun onCreate() {
        super.onCreate()
        val factory = DataSource.Factory {
            PartDataSource(DefaultHttpDataSource.Factory().setDefaultRequestProperties(repository.audioHeaders())
                .setConnectTimeoutMs(15000).setReadTimeoutMs(20000).createDataSource())
        }
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(factory))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        val savedVolume = MusicVolume.restored(volumePreferences.getFloat("decibels", 0f),
            volumePreferences.getBoolean("muted", false))
        PlaybackStatus.volume.value = savedVolume
        // Apply before preparing the first item, avoiding a burst at full gain.
        player.volume = savedVolume.gain
        volumeFader = VolumeFader(scope, { player.volume }, { player.volume = it })
        parts = PartPlayback(scope, { detail, part -> repository.audio(detail.video.bvid, part.cid) },
            object : PartPlayer {
                override fun select(detail: VideoDetail, index: Int, playWhenReady: Boolean) {
                    saveHistoryProgress()
                    recordedMediaId = null
                    player.stop()
                    // The cancellable pending source keeps the service in buffering/foreground.
                    player.setMediaItem(mediaItem(detail, index))
                    player.playWhenReady = playWhenReady
                    player.prepare()
                }
                override fun start(detail: VideoDetail, index: Int, stream: AudioStream, candidate: Int,
                    position: Long, playWhenReady: Boolean) {
                    player.setMediaItem(mediaItem(detail, index,
                        Uri.parse(stream.urls[candidate % stream.urls.size]), stream.mimeType), position)
                    player.prepare()
                    player.playWhenReady = playWhenReady
                }
                override fun stop(clear: Boolean) {
                    saveHistoryProgress()
                    player.playWhenReady = false
                    player.stop()
                    if (clear) player.clearMediaItems()
                }
                override fun replay() { player.seekTo(0); player.play() }
                override fun finish() { player.playWhenReady = false }
            })
        scope.launch {
            parts.state.collect { state ->
                PlaybackStatus.parts.value = state
                PlaybackStatus.resolving.value = state.loading
                PlaybackStatus.error.value = state.error
            }
        }
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val recoverable = error.errorCode in listOf(
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                        PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                    )
                parts.failed(player.currentMediaItem?.mediaId.orEmpty(),
                    "${error.message ?: "音频播放失败"}（${error.errorCode}）", recoverable, player.currentPosition)
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (reason != Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
                    parts.recordPlayIntent(playWhenReady)
            }
            override fun onEvents(player: Player, events: Player.Events) {
                val state = parts.state.value
                val mediaId = player.currentMediaItem?.mediaId
                if (player.isPlaying && !state.loading && state.error == null &&
                    mediaId == state.mediaId && mediaId != recordedMediaId) {
                    val detail = state.detail
                    val part = state.part
                    if (detail != null && part != null) {
                        runCatching { (application as BiliAudioApp).history.record(HistoryEntry(detail.video, part, player.currentPosition.coerceAtLeast(0))) }
                        recordedMediaId = mediaId
                    }
                }
                saveHistoryProgress()
                if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && player.playbackState == Player.STATE_ENDED)
                    parts.ended(player.repeatMode == Player.REPEAT_MODE_ONE)
            }
        })
        scope.launch { while (true) { delay(5000); saveHistoryProgress() } }
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, player).setSessionActivity(activity)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    if (controller.packageName != packageName && !controller.isTrusted)
                        return MediaSession.ConnectionResult.reject()
                    val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    if (controller.packageName == packageName) {
                        commands.add(VolumeCommands.set).add(VolumeCommands.toggleMute)
                        commands.add(PartCommands.play).add(PartCommands.select)
                            .add(PartCommands.previous).add(PartCommands.next).add(PartCommands.retry)
                    }
                    return MediaSession.ConnectionResult.accept(commands.build(),
                        MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                            .remove(Player.COMMAND_SET_VOLUME).remove(Player.COMMAND_SET_MEDIA_ITEM)
                            .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS).remove(Player.COMMAND_PREPARE).build())
                }
                override fun onCustomCommand(
                    session: MediaSession, controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand, args: Bundle
                ): ListenableFuture<SessionResult> {
                    if (controller.packageName != packageName)
                        return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                    if (customCommand.customAction in listOf(PartCommands.play.customAction,
                            PartCommands.select.customAction, PartCommands.previous.customAction,
                            PartCommands.next.customAction, PartCommands.retry.customAction)) {
                        val accepted = try {
                            when (customCommand.customAction) {
                                PartCommands.play.customAction -> {
                                    saveHistoryProgress()
                                    val detail = VideoDetailCodec.decode(args.getString(PartCommands.DETAIL)
                                        ?: error("视频信息缺失"))
                                    val cid = args.getLong(PartCommands.CID)
                                    val part = detail.parts.firstOrNull { it.cid == cid }
                                    val entry = (application as BiliAudioApp).history.entries.value
                                        .firstOrNull { it.key == "${detail.video.bvid}:$cid" }
                                    val position = if (args.getBoolean(PartCommands.RESUME))
                                        entry?.resumePosition(part?.durationSeconds ?: 0)
                                            ?: args.getLong(PartCommands.POSITION).coerceAtLeast(0)
                                        else 0
                                    parts.play(detail, cid, position)
                                }
                                PartCommands.select.customAction -> parts.select(args.getLong(PartCommands.CID))
                                PartCommands.previous.customAction -> parts.previous()
                                PartCommands.next.customAction -> parts.next()
                                else -> parts.retry(player.currentPosition)
                            }
                        } catch (_: Exception) { false }
                        return Futures.immediateFuture(SessionResult(if (accepted)
                            SessionResult.RESULT_SUCCESS else SessionError.ERROR_BAD_VALUE))
                    }
                    val current = PlaybackStatus.volume.value
                    val next = when (customCommand.customAction) {
                        VolumeCommands.set.customAction -> {
                            val db = args.getFloat(VolumeCommands.DECIBELS, Float.NaN)
                            if (!db.isFinite()) return Futures.immediateFuture(
                                SessionResult(SessionError.ERROR_BAD_VALUE))
                            current.adjusted(db)
                        }
                        VolumeCommands.toggleMute.customAction -> current.toggleMute()
                        else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                    }
                    setMusicVolume(next)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                override fun onPlayerCommandRequest(session: MediaSession, controller: MediaSession.ControllerInfo, playerCommand: Int): Int {
                    if (playerCommand == Player.COMMAND_STOP) {
                        parts.stop()
                    }
                    return androidx.media3.session.SessionResult.RESULT_SUCCESS
                }
            }).build()
    }
    private fun saveHistoryProgress() {
        if (!::player.isInitialized) return
        val key = recordedMediaId ?: return
        if (player.currentMediaItem?.mediaId != key) return
        val position = if (player.playbackState == Player.STATE_ENDED) 0 else player.currentPosition.coerceAtLeast(0)
        runCatching { (application as BiliAudioApp).history.updateProgress(key, position) }
    }
    private fun setMusicVolume(volume: MusicVolume) {
        PlaybackStatus.volume.value = volume
        volumeFader.fadeTo(volume.gain)
        volumeSaveJob?.cancel()
        volumeSaveJob = scope.launch { delay(250); saveMusicVolume() }
    }
    private fun saveMusicVolume() {
        val volume = PlaybackStatus.volume.value
        volumePreferences.edit().putFloat("decibels", volume.decibels)
            .putBoolean("muted", volume.muted).apply()
    }
    private fun mediaItem(detail: VideoDetail, index: Int, uri: Uri = Uri.parse("bili-pending://resolve"),
        mimeType: String = "audio/mp4"): MediaItem {
        val part = detail.parts[index]
        val title = if (detail.parts.size > 1) "${detail.video.title} · P${part.number} ${part.title}" else detail.video.title
        return MediaItem.Builder().setMediaId("${detail.video.bvid}:${part.cid}")
            .setUri(uri).setMimeType(mimeType).setMediaMetadata(MediaMetadata.Builder()
                .setTitle(title).setArtist(detail.video.author).setArtworkUri(Uri.parse(detail.video.cover)).build()).build()
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onDestroy() {
        saveHistoryProgress()
        saveMusicVolume()
        if (::parts.isInitialized) parts.stop()
        scope.cancel()
        session?.release()
        if (::player.isInitialized) player.release()
        PlaybackStatus.resolving.value = false
        PlaybackStatus.error.value = null
        PlaybackStatus.parts.value = PartPlaybackState()
        super.onDestroy()
    }
}
