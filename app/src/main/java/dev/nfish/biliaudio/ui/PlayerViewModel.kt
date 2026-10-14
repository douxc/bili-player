package dev.nfish.biliaudio.ui

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.nfish.biliaudio.BiliAudioApp
import dev.nfish.biliaudio.data.QrSession
import dev.nfish.biliaudio.data.Video
import dev.nfish.biliaudio.data.VideoDetail
import dev.nfish.biliaudio.data.VideoPart
import dev.nfish.biliaudio.data.BvidInput
import dev.nfish.biliaudio.data.HistoryEntry
import dev.nfish.biliaudio.playback.PlaybackService
import dev.nfish.biliaudio.playback.PlaybackStatus
import dev.nfish.biliaudio.playback.VolumeCommands
import dev.nfish.biliaudio.playback.PartCommands
import dev.nfish.biliaudio.playback.VideoDetailCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ScreenState(
    val query: String = "", val searched: String = "", val videos: List<Video> = emptyList(),
    val loading: Boolean = false, val page: Int = 0, val hasMore: Boolean = false,
    val searchError: String? = null, val detailLoading: String? = null, val detail: VideoDetail? = null,
    val loggedIn: Boolean = false, val loginOpen: Boolean = false, val qr: QrSession? = null,
    val loginMessage: String = "正在生成二维码…", val loginExpired: Boolean = false
)
data class PlayerState(
    val connected: Boolean = false, val mediaId: String = "", val title: String = "", val author: String = "",
    val cover: String = "", val playing: Boolean = false, val buffering: Boolean = false,
    val position: Long = 0, val duration: Long = 0, val repeat: Boolean = false
)

@UnstableApi
class PlayerViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = (app as BiliAudioApp).repository
    val history = (app as BiliAudioApp).history.entries
    private val _screen = MutableStateFlow(ScreenState(loggedIn = repository.isLoggedIn))
    val screen = _screen.asStateFlow()
    private val _player = MutableStateFlow(PlayerState())
    val player = _player.asStateFlow()
    val playbackError = PlaybackStatus.error.asStateFlow()
    val resolving = PlaybackStatus.resolving.asStateFlow()
    val volume = PlaybackStatus.volume.asStateFlow()
    val parts = PlaybackStatus.parts.asStateFlow()
    val canRetry = MutableStateFlow(false)
    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var loginJob: Job? = null
    private var searchEpoch = 0L
    private var detailEpoch = 0L
    private var controller: MediaController? = null
    private val connection = MediaController.Builder(app, SessionToken(app,
        ComponentName(app, PlaybackService::class.java)))
        .setListener(object : MediaController.Listener {
            override fun onDisconnected(controller: MediaController) {
                _player.update { it.copy(connected = false) }
                PlaybackStatus.error.value = "播放服务连接已断开，请重新打开应用"
            }
        }).buildAsync()
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { syncPlayer() }
    }
    init {
        connection.addListener({
            try {
                controller = connection.get()
                controller?.addListener(listener)
                syncPlayer()
            } catch (_: Exception) { PlaybackStatus.error.value = "无法连接播放服务" }
        }, { task -> android.os.Handler(app.mainLooper).post(task) })
        viewModelScope.launch { while (true) { syncPlayer(); delay(500) } }
        viewModelScope.launch { parts.collect { syncPlayer() } }
    }
    private fun syncPlayer() {
        val c = controller ?: return
        val metadata = c.mediaMetadata
        _player.value = PlayerState(
            connected = c.isConnected, mediaId = c.currentMediaItem?.mediaId.orEmpty(),
            title = metadata.title?.toString().orEmpty(), author = metadata.artist?.toString().orEmpty(),
            cover = metadata.artworkUri?.toString().orEmpty(), playing = c.isPlaying,
            buffering = c.playbackState == Player.STATE_BUFFERING,
            position = c.currentPosition.coerceAtLeast(0), duration = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0,
            repeat = c.repeatMode == Player.REPEAT_MODE_ONE
        )
        canRetry.value = parts.value.part != null
    }
    fun query(value: String) { _screen.update { it.copy(query = value) } }
    fun search(more: Boolean = false) {
        if (more && (_screen.value.loading || !_screen.value.hasMore)) return
        val keyword = if (more) _screen.value.searched else _screen.value.query.trim()
        if (keyword.isBlank()) return
        searchJob?.cancel()
        val epoch = ++searchEpoch
        val page = if (more) _screen.value.page + 1 else 1
        _screen.update { it.copy(loading = true, searchError = null,
            videos = if (more) it.videos else emptyList(), searched = keyword, hasMore = if (more) it.hasMore else false) }
        searchJob = viewModelScope.launch {
            try {
                val bvid = BvidInput.extract(keyword)
                if (!more && bvid != null) {
                    val detail = repository.detail(bvid)
                    _screen.update { it.copy(videos = listOf(detail.video), page = 1, hasMore = false) }
                } else {
                    val result = repository.search(keyword, page)
                    _screen.update { it.copy(videos = ((if (more) it.videos else emptyList()) + result.videos).distinctBy { v -> v.bvid },
                        page = result.page, hasMore = result.hasMore) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _screen.update { it.copy(searchError = e.message ?: "搜索失败，请重试") } }
            finally { if (epoch == searchEpoch) _screen.update { it.copy(loading = false) } }
        }
    }
    fun choose(video: Video) {
        detailJob?.cancel()
        val epoch = ++detailEpoch
        _screen.update { it.copy(detailLoading = video.bvid, detail = null) }
        PlaybackStatus.error.value = null
        detailJob = viewModelScope.launch {
            try {
                val detail = repository.detail(video.bvid)
                if (detail.parts.size == 1) play(detail, detail.parts.first())
                else _screen.update { it.copy(detail = detail) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { PlaybackStatus.error.value = e.message ?: "视频信息获取失败" }
            finally { if (epoch == detailEpoch) _screen.update { it.copy(detailLoading = null) } }
        }
    }
    fun dismissParts() { _screen.update { it.copy(detail = null) } }
    fun playHistory(entry: HistoryEntry) {
        cancelDetailRequest()
        val epoch = ++detailEpoch
        _screen.update { it.copy(detailLoading = entry.video.bvid) }
        PlaybackStatus.error.value = null
        detailJob = viewModelScope.launch {
            try {
                val detail = repository.detail(entry.video.bvid)
                if (epoch != detailEpoch) return@launch
                val part = detail.parts.firstOrNull { it.cid == entry.part.cid }
                    ?: error("历史记录中的分 P 已不可用")
                play(detail, part, refresh = true, historyEntry = entry)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (epoch == detailEpoch) PlaybackStatus.error.value = e.message ?: "历史记录播放失败"
            } finally {
                if (epoch == detailEpoch) _screen.update { it.copy(detailLoading = null) }
            }
        }
    }
    private fun cancelDetailRequest() {
        detailJob?.cancel(); detailEpoch++
        _screen.update { it.copy(detail = null, detailLoading = null) }
    }
    fun play(detail: VideoDetail, part: VideoPart, refresh: Boolean = false, historyEntry: HistoryEntry? = null) {
        cancelDetailRequest()
        val c = controller?.takeIf { it.isConnected } ?: return
        if (!refresh && parts.value.detail?.video?.bvid == detail.video.bvid) {
            c.sendCustomCommand(PartCommands.select, Bundle().apply { putLong(PartCommands.CID, part.cid) })
        } else c.sendCustomCommand(PartCommands.play, Bundle().apply {
            putString(PartCommands.DETAIL, VideoDetailCodec.encode(detail)); putLong(PartCommands.CID, part.cid)
            putBoolean(PartCommands.RESUME, historyEntry != null)
            putLong(PartCommands.POSITION, historyEntry?.resumePosition(part.durationSeconds) ?: 0)
        })
    }
    fun retryPlayback() {
        controller?.sendCustomCommand(PartCommands.retry, Bundle.EMPTY)
    }
    fun previousPart() {
        cancelDetailRequest(); controller?.sendCustomCommand(PartCommands.previous, Bundle.EMPTY)
    }
    fun nextPart() {
        cancelDetailRequest(); controller?.sendCustomCommand(PartCommands.next, Bundle.EMPTY)
    }
    fun openCurrentParts() {
        cancelDetailRequest()
        parts.value.detail?.let { detail -> _screen.update { it.copy(detail = detail) } }
    }
    fun togglePlay() {
        if (parts.value.error != null) { retryPlayback(); return }
        controller?.let { if (it.playWhenReady) it.pause() else { if (it.playbackState == Player.STATE_ENDED) it.seekTo(0); it.play() } }
    }
    fun seek(position: Long) { controller?.seekTo(position.coerceIn(0, _player.value.duration)) }
    fun repeat() { controller?.let { it.repeatMode = if (it.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE } }
    fun musicVolume(decibels: Float) {
        controller?.takeIf { it.isConnected }?.sendCustomCommand(VolumeCommands.set,
            Bundle().apply { putFloat(VolumeCommands.DECIBELS, decibels) })
    }
    fun toggleMusicMute() {
        controller?.takeIf { it.isConnected }?.sendCustomCommand(VolumeCommands.toggleMute, Bundle.EMPTY)
    }
    fun stop() { cancelDetailRequest(); controller?.stop(); PlaybackStatus.error.value = null; canRetry.value = false }
    fun openLogin() {
        loginJob?.cancel()
        _screen.update { it.copy(loginOpen = true, qr = null, loginMessage = "正在生成二维码…", loginExpired = false) }
        loginJob = viewModelScope.launch {
            try {
                val qr = repository.newQr()
                _screen.update { it.copy(qr = qr, loginMessage = "等待扫码") }
                repeat(90) {
                    delay(2000)
                    val status = repository.pollQr(qr.key)
                    when (status.code) {
                        0 -> { _screen.update { it.copy(loggedIn = true, loginOpen = false) }; return@launch }
                        86038 -> { _screen.update { it.copy(loginExpired = true, loginMessage = "二维码已过期") }; return@launch }
                        86090 -> _screen.update { it.copy(loginMessage = "已扫码，请在哔哩哔哩确认登录") }
                        86101 -> Unit
                        else -> { _screen.update { it.copy(loginExpired = true, loginMessage = status.message.ifBlank { "登录失败" }) }; return@launch }
                    }
                }
                _screen.update { it.copy(loginExpired = true, loginMessage = "二维码已过期") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _screen.update { it.copy(loginExpired = true, loginMessage = e.message ?: "登录失败") } }
        }
    }
    fun closeLogin() { loginJob?.cancel(); _screen.update { it.copy(loginOpen = false) } }
    fun logout() { repository.logout(); _screen.update { it.copy(loggedIn = false) } }
    override fun onCleared() {
        controller?.removeListener(listener)
        MediaController.releaseFuture(connection)
        super.onCleared()
    }
}
