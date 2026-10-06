package ua.vidbiy.app.alarm

import org.junit.Assert.assertEquals
import org.junit.Test

class RingTimeoutTest {

    @Test
    fun `перший дзвінок без відповіді відкладає себе сам`() {
        assertEquals(RingTimeout.AutoSnooze(1), ringTimeout(0))
    }

    @Test
    fun `другий дзвінок без відповіді відкладає себе ще раз`() {
        assertEquals(RingTimeout.AutoSnooze(2), ringTimeout(1))
    }

    @Test
    fun `після третього дзвінка будильник здається`() {
        assertEquals(RingTimeout.GiveUp, ringTimeout(MAX_AUTO_SNOOZES))
    }

    @Test
    fun `зіпсований лічильник не змушує дзвонити вічно`() {
        assertEquals(RingTimeout.GiveUp, ringTimeout(99))
    }

    @Test
    fun `разом три дзвінки`() {
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
