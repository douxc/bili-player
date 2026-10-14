package dev.nfish.biliaudio.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class BiliRepository(
    private val persistence: SessionPersistence,
    private val apiOrigin: String = "https://api.bilibili.com",
    private val passportOrigin: String = "https://passport.bilibili.com",
    private val clientBuilder: OkHttpClient.Builder = OkHttpClient.Builder()
) {
    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        const val REFERER = "https://www.bilibili.com/"
    }
    private val cookies = CookieSession(persistence)
    private val client = clientBuilder.cookieJar(cookies).connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    private val keyMutex = Mutex()
    @Volatile private var signatureKey: String? = null
    @Volatile private var keyTime = 0L
    val isLoggedIn: Boolean get() = cookies.hasSession()

    fun logout() { cookies.clear(); signatureKey = null }
    fun audioHeaders(): Map<String, String> = mapOf("User-Agent" to USER_AGENT, "Referer" to REFERER)

    private suspend fun request(path: String, params: Map<String, String> = emptyMap(), passport: Boolean = false): JSONObject {
        val base = if (passport) passportOrigin else apiOrigin
        val url = (base + path).toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        return raw(url)
    }
    private suspend fun raw(url: HttpUrl): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT)
            .header("Referer", REFERER).build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw BiliException(response.code, when (response.code) {
                403, 412, 429 -> "B站限制了这次请求，请稍后重试或登录"
                else -> "网络请求失败（HTTP ${response.code}）"
            })
            val body = response.body?.string() ?: throw IOException("B站返回空响应")
            try { JSONObject(body) } catch (_: Exception) { throw IOException("B站返回了非 JSON 数据，请稍后重试") }
        }
    }
    private fun checked(root: JSONObject): JSONObject {
        val code = root.optInt("code", -1)
        if (code != 0) throw BiliException(code, when (code) {
            -101 -> "B站要求登录，请使用右上角账号按钮登录"
            -403, -412, -352, -799 -> "B站限制了这次请求，请稍后重试或登录"
            -400 -> "B站请求参数或签名失效，请重试"
            -404, 62002 -> "视频不存在或已被删除"
            else -> "${root.optString("message", "B站请求失败")}（$code）"
        })
        val data = root.optJSONObject("data") ?: throw IOException("B站响应缺少数据")
        if (data.optString("v_voucher").isNotBlank()) throw BiliException(-352, "B站要求安全验证，请在官方 App 完成验证后重试")
        return data
    }
    private suspend fun wbiKey(force: Boolean = false): String = keyMutex.withLock {
        signatureKey?.takeIf { !force && System.currentTimeMillis() - keyTime < 30 * 60 * 1000 }?.let { return@withLock it }
        if (!cookies.hasBuvid()) {
            try {
                val finger = checked(request("/x/frontend/finger/spi"))
                cookies.addDevice(finger.optString("b_3"), finger.optString("b_4"))
            } catch (e: CancellationException) { throw e } catch (_: IOException) { /* nav can still work */ }
        }
        // Anonymous nav has code -101 but still supplies the WBI public keys.
        val nav = request("/x/web-interface/nav")
        val images = nav.optJSONObject("data")?.optJSONObject("wbi_img")
            ?: throw IOException("无法获取 B站签名信息，请稍后重试")
        fun keyFrom(name: String): String = images.optString(name).substringAfterLast('/').substringBefore('.')
        Wbi.mixinKey(keyFrom("img_url"), keyFrom("sub_url")).also {
            signatureKey = it; keyTime = System.currentTimeMillis()
        }
    }
    private suspend fun signed(path: String, params: Map<String, String>): JSONObject {
        suspend fun call(force: Boolean): JSONObject {
            val query = Wbi.sign(params, wbiKey(force), System.currentTimeMillis() / 1000)
            return checked(raw((apiOrigin + path + "?" + query).toHttpUrl()))
        }
        return try { call(false) } catch (e: BiliException) {
            // Refresh a stale public key once; never loop on rate limits or account restrictions.
            if (e.code == -400 || e.code == -403) call(true) else throw e
        }
    }
    suspend fun search(keyword: String, page: Int = 1): SearchPage {
        val data = signed("/x/web-interface/wbi/search/type", mapOf(
            "search_type" to "video", "keyword" to keyword.trim(), "page" to page.toString(), "order" to "totalrank"
        ))
        val result = data.optJSONArray("result") ?: JSONArray()
        val videos = (0 until result.length()).mapNotNull { index ->
            val row = result.optJSONObject(index) ?: return@mapNotNull null
            val bvid = row.optString("bvid")
            if (row.optString("type", "video") != "video" || BvidInput.extract(bvid) == null) return@mapNotNull null
            Video(bvid, cleanTitle(row.optString("title")), row.optString("author"), https(row.optString("pic")), row.optString("duration"))
        }.distinctBy { it.bvid }
        val numPages = data.optInt("numPages", -1)
        return SearchPage(videos, page, if (numPages >= 0) page < numPages else videos.size >= 20)
    }
    suspend fun detail(bvid: String): VideoDetail {
        require(BvidInput.extract(bvid) == bvid) { "BV 号格式错误" }
        val data = signed("/x/web-interface/wbi/view", mapOf("bvid" to bvid))
        val video = Video(bvid, data.optString("title"), data.optJSONObject("owner")?.optString("name").orEmpty(),
            https(data.optString("pic")), formatTime(data.optLong("duration") * 1000))
        val pages = data.optJSONArray("pages") ?: JSONArray()
        val parts = (0 until pages.length()).mapNotNull { index ->
            val p = pages.optJSONObject(index) ?: return@mapNotNull null
            val cid = p.optLong("cid")
            if (cid <= 0) null else VideoPart(cid, p.optInt("page", index + 1), p.optString("part"), p.optLong("duration"))
        }
        if (parts.isEmpty()) throw IOException("视频没有可播放的分 P")
        return VideoDetail(video, parts)
    }
    suspend fun audio(bvid: String, cid: Long): AudioStream {
        require(BvidInput.extract(bvid) == bvid && cid > 0) { "曲目标识无效" }
        val data = signed("/x/player/wbi/playurl", mapOf(
            "bvid" to bvid, "cid" to cid.toString(), "fnval" to "16", "fnver" to "0", "fourk" to "0", "qn" to "64"
        ))
        val audio = data.optJSONObject("dash")?.optJSONArray("audio") ?: JSONArray()
        // Standard AAC is widely supported; don't request premium Dolby/FLAC or transport video.
        val stream = (0 until audio.length()).mapNotNull { audio.optJSONObject(it) }
            .filter { it.optString("mimeType", it.optString("mime_type")).contains("audio/mp4") || it.optString("codecs").startsWith("mp4a") }
            .maxByOrNull { it.optLong("bandwidth") }
            ?: throw IOException("该视频没有可用的独立音频流，可能需要登录或没有播放权限")
        val primary = stream.optString("baseUrl", stream.optString("base_url"))
        val backups = stream.optJSONArray("backupUrl") ?: stream.optJSONArray("backup_url") ?: JSONArray()
        val urls = (listOf(primary) + (0 until backups.length()).map { backups.optString(it) })
            .map(::https).filter { it.startsWith("https://") }.distinct()
        if (urls.isEmpty()) throw IOException("B站未返回可用音频地址")
        return AudioStream(urls, "audio/mp4")
    }
    suspend fun newQr(): QrSession {
        val d = checked(request("/x/passport-login/web/qrcode/generate", passport = true))
        val qr = QrSession(d.optString("qrcode_key"), d.optString("url"))
        if (qr.key.isBlank() || qr.url.isBlank()) throw IOException("登录二维码生成失败")
        return qr
    }
    suspend fun pollQr(key: String): QrStatus {
        val d = checked(request("/x/passport-login/web/qrcode/poll", mapOf("qrcode_key" to key), passport = true))
        val code = d.optInt("code", -1)
        if (code == 0) {
            if (!cookies.hasSession()) throw IOException("登录成功但未收到登录凭据，请重新扫码")
            cookies.persist(); signatureKey = null
        }
        return QrStatus(code, d.optString("message"))
    }
    private fun https(url: String) = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("http://") -> "https://" + url.removePrefix("http://")
        else -> url
    }
    private fun cleanTitle(value: String) = value.replace(Regex("<[^>]*>"), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWith(Result.failure(e))
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}

private class CookieSession(private val store: SessionPersistence) : CookieJar {
    private val jar = mutableMapOf<String, Cookie>()
    init {
        store.read().split('\n').filter { it.isNotBlank() }.forEach { line ->
            Cookie.parse(BiliRepository.REFERER.toHttpUrl(), line)?.let { jar[it.name] = it }
        }
    }
    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        // All auth requests target Bilibili. Test localhost is allowed only through the injectable API origin.
        cookies.forEach { jar[it.name] = it }
    }
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        jar.entries.removeAll { it.value.expiresAt <= System.currentTimeMillis() }
        return jar.values.filter { it.matches(url) }
    }
    @Synchronized fun hasSession() = jar["SESSDATA"]?.let { it.value.isNotBlank() && it.expiresAt > System.currentTimeMillis() } == true
    @Synchronized fun hasBuvid() = jar["buvid3"] != null
    @Synchronized fun addDevice(b3: String, b4: String) {
        listOf("buvid3" to b3, "buvid4" to b4).filter { it.second.isNotBlank() }.forEach { (name, value) ->
            jar[name] = Cookie.Builder().name(name).value(value).domain("bilibili.com").path("/").secure().build()
        }
    }
    @Synchronized fun persist() { store.write(jar.values.joinToString("\n") { it.toString() }) }
    @Synchronized fun clear() { jar.clear(); store.clear() }
}
