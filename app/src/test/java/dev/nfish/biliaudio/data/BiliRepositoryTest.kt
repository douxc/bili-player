package dev.nfish.biliaudio.data

import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class BiliRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repo: BiliRepository
    private val persistence = object : SessionPersistence {
        var value = ""
        override fun read() = value
        override fun write(value: String) { this.value = value }
        override fun clear() { value = "" }
    }
    private val nav = """{"code":-101,"data":{"wbi_img":{"img_url":"https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png","sub_url":"https://i0.hdslb.com/bfs/wbi/4932c2affd746b4d30f546d6afd7ff4a.png"}}}"""
    private val search = """{"code":0,"data":{"numPages":2,"result":[{"type":"video","bvid":"BV1xx411c7mD","title":"<em class=\"keyword\">音乐</em> &amp; Live","author":"UP","pic":"//i0.hdslb.com/cover.jpg","duration":"3:05"}]}}"""
    @Before fun setup() {
        server = MockWebServer(); server.start()
        val origin = server.url("/").toString().trimEnd('/')
        repo = BiliRepository(persistence, origin, origin)
    }
    @After fun teardown() { server.shutdown() }
    private fun json(value: String) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(value)) }
    private fun bootstrap() { json("""{"code":0,"data":{"b_3":"device-test","b_4":"device-test-4"}}"""); json(nav) }

    @Test fun anonymousNavAndSignedSearchParseRealResponseShape() = runBlocking {
        bootstrap(); json(search)
        val result = repo.search("音乐")
        assertEquals("音乐 & Live", result.videos.single().title)
        assertEquals("https://i0.hdslb.com/cover.jpg", result.videos.single().cover)
        assertTrue(result.hasMore)
        server.takeRequest(); server.takeRequest()
        val request = server.takeRequest()
        assertEquals("/x/web-interface/wbi/search/type", request.requestUrl!!.encodedPath)
        assertEquals("音乐", request.requestUrl!!.queryParameter("keyword"))
        assertEquals(32, request.requestUrl!!.queryParameter("w_rid")!!.length)
        assertEquals(BiliRepository.REFERER, request.getHeader("Referer"))
        assertTrue(persistence.value.isEmpty())
    }
    @Test fun refreshesStaleWbiKeyOnce() = runBlocking {
        bootstrap(); json("""{"code":-400,"message":"bad signature"}"""); json(nav); json(search)
        assertEquals(1, repo.search("音乐").videos.size)
        assertEquals(5, server.requestCount)
    }
    @Test fun doesNotLoopOnRateLimit() = runBlocking {
        bootstrap(); json("""{"code":-412,"message":"risk control"}""")
        val error = runCatching { repo.search("音乐") }.exceptionOrNull()
        assertTrue(error is BiliException)
        assertEquals(-412, (error as BiliException).code)
        assertEquals(3, server.requestCount)
    }
    @Test fun selectsAudioAndPreservesHttpsBackups() = runBlocking {
        bootstrap()
        json("""{"code":0,"data":{"dash":{"audio":[{"bandwidth":64000,"mimeType":"audio/mp4","codecs":"mp4a.40.2","baseUrl":"https://audio.bilivideo.com/low.m4s"},{"bandwidth":192000,"mimeType":"audio/mp4","codecs":"mp4a.40.2","baseUrl":"http://audio.bilivideo.com/high.m4s","backupUrl":["https://backup.bilivideo.com/high.m4s"]}]}}}""")
        val stream = repo.audio("BV1xx411c7mD", 123)
        assertEquals(listOf("https://audio.bilivideo.com/high.m4s", "https://backup.bilivideo.com/high.m4s"), stream.urls)
        server.takeRequest(); server.takeRequest()
        val request = server.takeRequest()
        assertEquals("123", request.requestUrl!!.queryParameter("cid"))
        assertEquals("16", request.requestUrl!!.queryParameter("fnval"))
    }
    @Test fun missingAudioIsAVisibleFailure() = runBlocking {
        bootstrap(); json("""{"code":0,"data":{"dash":{"video":[{"baseUrl":"https://video.bilivideo.com/video.m4s"}]}}}""")
        val error = runCatching { repo.audio("BV1xx411c7mD", 123) }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("独立音频流"))
    }
    @Test fun parsesMultiPartVideo() = runBlocking {
        bootstrap()
        json("""{"code":0,"data":{"title":"合集","pic":"//i0.hdslb.com/a.jpg","duration":360,"owner":{"name":"UP"},"pages":[{"cid":11,"page":1,"part":"第一首","duration":180},{"cid":22,"page":2,"part":"第二首","duration":180}]}}""")
        val detail = repo.detail("BV1xx411c7mD")
        assertEquals(listOf(11L, 22L), detail.parts.map { it.cid })
    }
    @Test fun cancelledSearchCancelsNetworkCall() = runBlocking {
        bootstrap()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val deferred = async(Dispatchers.Default) { repo.search("音乐") }
        repeat(3) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
        deferred.cancel()
        withTimeout(1500) { deferred.join() }
        assertTrue(deferred.isCancelled)
    }
    @Test fun qrLoginNeverClaimsSuccessWithoutSessionCookie() = runBlocking {
        json("""{"code":0,"data":{"code":0,"message":"OK"}}""")
        val error = runCatching { repo.pollQr("test-key") }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("未收到登录凭据"))
        assertFalse(repo.isLoggedIn)
    }
    @Test fun successfulQrPersistsOnlyAccountCookiesAndLogoutClearsThem() = runBlocking {
        server.enqueue(MockResponse().addHeader("Set-Cookie", "SESSDATA=fake-session; Path=/; Max-Age=3600; HttpOnly")
            .setBody("""{"code":0,"data":{"code":0,"message":"OK"}}"""))
        assertEquals(0, repo.pollQr("test-key").code)
        assertTrue(repo.isLoggedIn)
        assertTrue(persistence.value.contains("SESSDATA=fake-session"))
        repo.logout()
        assertFalse(repo.isLoggedIn)
        assertTrue(persistence.value.isEmpty())
    }
    @Test fun securityVoucherIsNotReportedAsAnEmptySearch() = runBlocking {
        bootstrap(); json("""{"code":0,"data":{"v_voucher":"challenge"}}""")
        val error = runCatching { repo.search("音乐") }.exceptionOrNull()
        assertTrue(error is BiliException)
        assertEquals(-352, (error as BiliException).code)
    }
}
