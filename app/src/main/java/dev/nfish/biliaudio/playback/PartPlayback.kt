package dev.nfish.biliaudio.playback

import dev.nfish.biliaudio.data.AudioStream
import dev.nfish.biliaudio.data.VideoDetail
import dev.nfish.biliaudio.data.VideoPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

data class PartPlaybackState(
    val detail: VideoDetail? = null,
    val index: Int = -1,
    val loading: Boolean = false,
    val playWhenReady: Boolean = false,
    val error: String? = null
) {
    val part: VideoPart? get() = detail?.parts?.getOrNull(index)
    val mediaId: String get() = part?.let { "${detail?.video?.bvid}:${it.cid}" }.orEmpty()
    val hasPrevious: Boolean get() = index > 0 && part != null
    val hasNext: Boolean get() = detail?.parts?.let { index >= 0 && index < it.lastIndex } ?: false
}

interface PartPlayer {
    fun select(detail: VideoDetail, index: Int, playWhenReady: Boolean)
    fun start(detail: VideoDetail, index: Int, stream: AudioStream, candidate: Int,
        position: Long, playWhenReady: Boolean)
    fun stop(clear: Boolean)
    fun replay()
    fun finish()
}

/** One video's parts, held by the service. Only the current audio URL is resolved. */
class PartPlayback(
    private val scope: CoroutineScope,
    private val resolve: suspend (VideoDetail, VideoPart) -> AudioStream,
    private val output: PartPlayer
) {
    private val _state = MutableStateFlow(PartPlaybackState())
    val state = _state.asStateFlow()
    private var job: Job? = null
    private var epoch = 0L
    private var candidate = 0
    private val retries = RetryBudget()

    fun play(detail: VideoDetail, cid: Long): Boolean {
        require(detail.parts.isNotEmpty() && detail.parts.all { it.cid > 0 })
        require(detail.parts.map { it.cid }.distinct().size == detail.parts.size)
        val index = detail.parts.indexOfFirst { it.cid == cid }
        if (index < 0) return false
        load(detail, index, playWhenReady = true)
        return true
    }

    fun select(cid: Long): Boolean = _state.value.detail?.let { play(it, cid) } ?: false
    fun previous(): Boolean = move(-1)
    fun next(): Boolean = move(1)
    private fun move(direction: Int): Boolean {
        val current = _state.value
        val detail = current.detail ?: return false
        val target = current.index + direction
        if (target !in detail.parts.indices) return false
        load(detail, target, playWhenReady = true)
        return true
    }

    fun retry(position: Long = 0): Boolean {
        val current = _state.value
        val detail = current.detail ?: return false
        if (current.part == null) return false
        load(detail, current.index, position = position, playWhenReady = true)
        return true
    }

    /** User/system pause during resolution must win over the original selection. */
    fun recordPlayIntent(value: Boolean) {
        if (_state.value.part != null) _state.value = _state.value.copy(playWhenReady = value)
    }

    fun ended(repeatOne: Boolean) {
        val current = _state.value
        if (current.loading || current.error != null || !current.playWhenReady) return
        if (repeatOne) { output.replay(); return }
        val detail = current.detail ?: return
        if (current.hasNext) load(detail, current.index + 1, playWhenReady = true)
        else {
            _state.value = current.copy(playWhenReady = false)
            output.finish()
        }
    }

    fun failed(mediaId: String, message: String, recoverable: Boolean, position: Long) {
        val current = _state.value
        if (current.loading || current.part == null || mediaId != current.mediaId) return
        val detail = current.detail ?: return
        if (recoverable && retries.take()) {
            candidate++
            load(detail, current.index, position, current.playWhenReady, refresh = true)
        } else if (current.hasNext) {
            // Failure always moves forward, including while single-part repeat is enabled.
            load(detail, current.index + 1, playWhenReady = current.playWhenReady)
        } else failAtEnd(message)
    }

    fun stop() {
        epoch++; job?.cancel()
        _state.value = PartPlaybackState()
        output.stop(clear = true)
    }

    private fun load(detail: VideoDetail, startIndex: Int, position: Long = 0,
        playWhenReady: Boolean, refresh: Boolean = false) {
        job?.cancel()
        val selection = ++epoch
        if (!refresh) { retries.reset(); candidate = 0 }
        _state.value = PartPlaybackState(detail, startIndex, loading = true, playWhenReady = playWhenReady)
        job = scope.launch {
            // ExoPlayer reports an error before its IDLE event. Mutate it on the next turn.
            yield()
            if (selection != epoch) return@launch
            var index = startIndex
            var startPosition = position.coerceAtLeast(0)
            output.select(detail, index, _state.value.playWhenReady)
            if (refresh) delay(1200)
            while (selection == epoch) {
                try {
                    val stream = resolve(detail, detail.parts[index])
                    require(stream.urls.isNotEmpty()) { "没有可播放的音频地址" }
                    if (selection != epoch) return@launch
                    _state.value = _state.value.copy(loading = false, error = null)
                    output.start(detail, index, stream, candidate, startPosition, _state.value.playWhenReady)
                    return@launch
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (selection != epoch) return@launch
                    if (index == detail.parts.lastIndex) {
                        failAtEnd(e.message ?: "音源解析失败")
                        return@launch
                    }
                    index++
                    candidate = 0; retries.reset(); startPosition = 0
                    _state.value = _state.value.copy(index = index, loading = true, error = null)
                    output.select(detail, index, _state.value.playWhenReady)
                    yield()
                }
            }
        }
    }

    private fun failAtEnd(message: String) {
        _state.value = _state.value.copy(loading = false, playWhenReady = false,
            error = "已尝试至最后一 P，播放失败：$message")
        output.stop(clear = false)
    }
}
