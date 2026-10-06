package ua.vidbiy.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NumberWheelTest {

    @Test
    fun `на старті значення стоїть у центральному рядку`() {
        for (count in listOf(24, 60)) {
            for (value in 0 until count) {
                val centre = wheelTopIndex(value, count) + WHEEL_VISIBLE_ROWS / 2
                assertEquals(value, wheelValue(centre, count))
            }
        }
    }

    @Test
    fun `значення йдуть по колу`() {
        val top = wheelTopIndex(23, 24) + WHEEL_VISIBLE_ROWS / 2
        assertEquals(0, wheelValue(top + 1, 24))
        assertEquals(22, wheelValue(top - 1, 24))
    }
}
