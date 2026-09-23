package ua.vidbiy.app.alarm

import org.junit.Assert.assertEquals
import org.junit.Test
import ua.vidbiy.app.data.Alarm
import java.time.LocalDateTime

class AlarmScheduleTest {

    // 2026-09-23 — середа.
    private val wednesdayNoon = LocalDateTime.of(2026, 9, 23, 12, 0)

    @Test
    fun `одноразовий будильник на сьогодні, якщо час ще не минув`() {
        val alarm = Alarm(hour = 23, minute = 30)

        assertEquals(
            LocalDateTime.of(2026, 9, 23, 23, 30),
            alarm.nextTriggerAt(wednesdayNoon),
        )
    }

    @Test
    fun `одноразовий будильник переноситься на завтра, якщо час минув`() {
        val alarm = Alarm(hour = 7, minute = 0)

        assertEquals(
            LocalDateTime.of(2026, 9, 24, 7, 0),
            alarm.nextTriggerAt(wednesdayNoon),
        )
    }

    @Test
    fun `щоденний будильник бере найближчий день`() {
        val alarm = Alarm(hour = 7, minute = 0, days = setOf(1, 2, 3, 4, 5, 6, 7))

        assertEquals(
            LocalDateTime.of(2026, 9, 24, 7, 0),
            alarm.nextTriggerAt(wednesdayNoon),
        )
    }

    @Test
    fun `будильник на будні з понеділка після п'ятниці чекає до понеділка`() {
        val fridayEvening = LocalDateTime.of(2026, 9, 25, 22, 0)
        val alarm = Alarm(hour = 7, minute = 0, days = setOf(1, 2, 3, 4, 5))

        assertEquals(
            LocalDateTime.of(2026, 9, 28, 7, 0),
            alarm.nextTriggerAt(fridayEvening),
        )
    }

    @Test
    fun `будильник раз на тиждень у день, що вже минув, переноситься на наступний тиждень`() {
        val alarm = Alarm(hour = 7, minute = 0, days = setOf(3))

        assertEquals(
            LocalDateTime.of(2026, 9, 30, 7, 0),
            alarm.nextTriggerAt(wednesdayNoon),
        )
    }

    @Test
    fun `крайній час — це час будильника плюс очікування`() {
        val alarm = Alarm(hour = 7, minute = 0, maxWaitMinutes = 120)
        val trigger = LocalDateTime.of(2026, 9, 24, 7, 0)

        assertEquals(LocalDateTime.of(2026, 9, 24, 9, 0), alarm.deadlineAt(trigger))
    }

    @Test
    fun `крайній час може перейти через північ`() {
        val alarm = Alarm(hour = 23, minute = 30, maxWaitMinutes = 60)
        val trigger = LocalDateTime.of(2026, 9, 23, 23, 30)

        assertEquals(LocalDateTime.of(2026, 9, 24, 0, 30), alarm.deadlineAt(trigger))
    }
}
