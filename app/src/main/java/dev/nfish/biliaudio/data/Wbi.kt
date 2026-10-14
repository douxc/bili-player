package dev.nfish.biliaudio.data

import java.net.URLEncoder
import java.security.MessageDigest

object Wbi {
    private val permutation = intArrayOf(
        46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,
        27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13,
        37,48,7,16,24,55,40,61,26,17,0,1,60,51,30,4,
        22,25,54,21,56,59,6,63,57,62,11,36,20,34,44,52
    )
    fun mixinKey(imgKey: String, subKey: String): String {
        val input = imgKey + subKey
        require(input.length == 64) { "B站签名密钥格式异常" }
        return permutation.take(32).map { input[it] }.joinToString("")
    }
    fun sign(params: Map<String, String>, key: String, seconds: Long): String {
        val sorted = (params + ("wts" to seconds.toString())).toSortedMap()
        val query = sorted.entries.joinToString("&") { (name, value) ->
            val cleanValue = value.filterNot { it in "!'()*" }
            "${encode(name)}=${encode(cleanValue)}"
        }
        val hash = MessageDigest.getInstance("MD5").digest((query + key).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return "$query&w_rid=$hash"
    }
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
        .replace("+", "%20").replace("*", "%2A").replace("%7E", "~")
}
