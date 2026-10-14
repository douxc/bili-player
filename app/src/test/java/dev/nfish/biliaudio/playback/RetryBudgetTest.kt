package dev.nfish.biliaudio.playback

import org.junit.Assert.*
import org.junit.Test

class RetryBudgetTest {
    @Test fun repeatedNetworkFailuresCannotRetryIndefinitely() {
        val budget = RetryBudget()
        assertTrue(budget.take()); assertTrue(budget.take())
        repeat(10) { assertFalse(budget.take()) }
        budget.reset()
        assertTrue(budget.take())
    }
}
