package dev.nfish.biliaudio.data

import org.junit.Assert.*
import org.junit.Test

class WbiTest {
    private val img = "7cd084941338484aae1ad9425b84077c"
    private val sub = "4932c2affd746b4d30f546d6afd7ff4a"
    @Test fun publicSignatureVector() {
        val key = Wbi.mixinKey(img, sub)
        assertEquals("4d1d6124af3cd0b24440937a704f4ad8", key)
        assertEquals("bar=514&baz=1919810&foo=114&wts=1702204169&w_rid=c2d1ad0085bce959b32d63d4d54a18ea",
            Wbi.sign(mapOf("foo" to "114", "bar" to "514", "baz" to "1919810"), key, 1702204169))
    }
    @Test fun chineseAndSpacesUseRfc3986EncodingAndRemoveForbiddenCharacters() {
        val signed = Wbi.sign(mapOf("keyword" to "音乐 a!'()*"), Wbi.mixinKey(img, sub), 1702204169)
        assertTrue(signed.startsWith("keyword=%E9%9F%B3%E4%B9%90%20a&wts=1702204169&"))
        assertFalse(signed.contains("+"))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectMalformedPublicKeys() { Wbi.mixinKey("short", "short") }
    @Test fun extractVideoIdentifierFromShareText() {
        assertEquals("BV1xx411c7mD", BvidInput.extract("视频分享 https://www.bilibili.com/video/BV1xx411c7mD/?p=2"))
        assertNull(BvidInput.extract("BVshort"))
        assertNull(BvidInput.extract("prefixBV1xx411c7mDmore"))
    }
    @Test fun durationsSupportLongAudio() {
        assertEquals("0:00", formatTime(-100))
        assertEquals("3:05", formatTime(185_000))
        assertEquals("1:01:01", formatTime(3_661_000))
    }
}
