package ua.vidbiy.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/** «Наступний дзвінок …» у діалозі FR-7b і після збереження: який день назвати. */
class FormattingTest {

    // 2026-10-07 — середа.
    private val wednesdayEvening = LocalDateTime.of(2026, 10, 7, 22, 0)

    @Test
    fun `сьогодні і завтра — словами`() {
        assertEquals(NextRingDay.TODAY, nextRingDay(LocalDateTime.of(2026, 10, 7, 23, 0), wednesdayEvening))
        assertEquals(NextRingDay.TOMORROW, nextRingDay(LocalDateTime.of(2026, 10, 8, 6, 0), wednesdayEvening))
    }

    @Test
    fun `у межах тижня — день тижня`() {
        assertEquals(NextRingDay.WEEKDAY, nextRingDay(LocalDateTime.of(2026, 10, 9, 6, 0), wednesdayEvening))
        assertEquals(NextRingDay.WEEKDAY, nextRingDay(LocalDateTime.of(2026, 10, 13, 6, 0), wednesdayEvening))
    }

    @Test
    fun `за тиждень і далі — дата, бо день тижня вже неоднозначний`() {
        assertEquals(NextRingDay.DATE, nextRingDay(LocalDateTime.of(2026, 10, 14, 6, 0), wednesdayEvening))
    }

    @Test
    fun `час — завжди по дві цифри`() {
        assertEquals("06:05", formatTime(6, 5))
    }
}
