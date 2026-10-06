package ua.vidbiy.app.alarm

import org.junit.Assert.assertEquals
import org.junit.Test

class RingTimeoutTest {

    @Test
    fun `first unanswered ring snoozes itself`() {
        assertEquals(RingTimeout.AutoSnooze(1), ringTimeout(0))
    }

    @Test
    fun `second unanswered ring snoozes once more`() {
        assertEquals(RingTimeout.AutoSnooze(2), ringTimeout(1))
    }

    @Test
    fun `third unanswered ring gives up`() {
        assertEquals(RingTimeout.GiveUp, ringTimeout(MAX_AUTO_SNOOZES))
    }

    @Test
    fun `a broken counter never rings forever`() {
        assertEquals(RingTimeout.GiveUp, ringTimeout(99))
    }

    @Test
    fun `three ring periods in total`() {
        var repeats = 0
        var rings = 1
        while (true) {
            val next = ringTimeout(repeats) as? RingTimeout.AutoSnooze ?: break
            repeats = next.autoRepeats
            rings++
        }
        assertEquals(3, rings)
    }
}
