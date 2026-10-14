package dev.nfish.biliaudio.playback

/** One bounded budget per part or explicit retry, not per READY/error transition. */
class RetryBudget(private val limit: Int = 2) {
    private var remaining = limit
    fun reset() { remaining = limit }
    fun take(): Boolean {
        if (remaining == 0) return false
        remaining--
        return true
    }
}
