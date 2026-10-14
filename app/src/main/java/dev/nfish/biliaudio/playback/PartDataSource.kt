package dev.nfish.biliaudio.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch

/** Keep ExoPlayer buffering while the service resolves a part, without network traffic. */
@UnstableApi
class PartDataSource(private val http: DataSource) : DataSource {
    private var current: DataSource = http
    override fun addTransferListener(listener: TransferListener) { http.addTransferListener(listener) }
    override fun open(dataSpec: DataSpec): Long {
        current = if (dataSpec.uri.scheme == "bili-pending") PendingDataSource() else http
        return current.open(dataSpec)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current.read(buffer, offset, length)
    override fun getUri(): Uri? = current.uri
    override fun getResponseHeaders(): Map<String, List<String>> = current.responseHeaders
    override fun close() { current.close() }

    private class PendingDataSource : DataSource {
        private val released = CountDownLatch(1)
        private var uri: Uri? = null
        override fun addTransferListener(listener: TransferListener) = Unit
        override fun getUri(): Uri? = uri
        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            try { released.await() }
            catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("分 P 加载已取消").apply { initCause(e) }
            }
            throw InterruptedIOException("分 P 音源已替换")
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            throw InterruptedIOException("分 P 音源尚未解析")
        override fun close() { released.countDown() }
    }
}
