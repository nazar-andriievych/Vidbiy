package ua.vidbiy.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleWarningTest {
    private val confirmed = 1_000_000_000_000L

    @Test
    fun `справні дані до 90 с — без попередження`() {
        // Знімок раз на 60 с + опитування раз на 30 с: звичайний вік до ~90 с.
        assertFalse(showsStaleWarning(confirmed, confirmed + 55_000))
        assertFalse(showsStaleWarning(confirmed, confirmed + 90_000))
        assertFalse(showsStaleWarning(confirmed, confirmed + 120_000))
    }

    @Test
    fun `понад 120 с — попередження, за хвилину до дзвінка`() {
        assertTrue(showsStaleWarning(confirmed, confirmed + 120_001))
        assertTrue(showsStaleWarning(confirmed, confirmed + 179_000))
    }

    @Test
    fun `ще немає даних — не попередження, а стан перевірки`() {
        assertFalse(showsStaleWarning(null, confirmed))
    }
}
