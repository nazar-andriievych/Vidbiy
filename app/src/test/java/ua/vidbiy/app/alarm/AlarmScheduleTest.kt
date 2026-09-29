package ua.vidbiy.app.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json
import ua.vidbiy.app.data.Alarm
import ua.vidbiy.app.data.withoutPastDate
import java.time.LocalDate
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
    fun `крайній час абсолютний і того ж дня, якщо пізніший за будильник`() {
        val alarm = Alarm(hour = 7, minute = 0, deadlineMinute = 9 * 60)
        val trigger = LocalDateTime.of(2026, 9, 24, 7, 0)

        assertEquals(LocalDateTime.of(2026, 9, 24, 9, 0), alarm.deadlineAfter(trigger))
    }

    @Test
    fun `крайній час раніший за будильник — це наступна доба`() {
        val alarm = Alarm(hour = 23, minute = 0, deadlineMinute = 60)
        val trigger = LocalDateTime.of(2026, 9, 23, 23, 0)

        assertEquals(LocalDateTime.of(2026, 9, 24, 1, 0), alarm.deadlineAfter(trigger))
    }

    @Test
    fun `крайнього часу за замовчуванням немає`() {
        assertNull(Alarm(hour = 7, minute = 0).deadlineAfter(LocalDateTime.of(2026, 9, 24, 7, 0)))
    }

    @Test
    fun `будильник з датою спрацьовує саме в ту дату`() {
        val alarm = Alarm(hour = 7, minute = 0, date = LocalDate.of(2026, 10, 9))

        assertEquals(LocalDateTime.of(2026, 10, 9, 7, 0), alarm.nextTriggerAt(wednesdayNoon))
    }

    @Test
    fun `будильник з сьогоднішньою датою, час якого минув, не має спрацювання`() {
        val alarm = Alarm(hour = 7, minute = 0, date = LocalDate.of(2026, 9, 23))

        assertNull(alarm.nextTriggerAt(wednesdayNoon))
    }

    @Test
    fun `минула дата знімається, лишається одноразовий будильник без дати`() {
        val alarm = Alarm(hour = 7, minute = 0, date = LocalDate.of(2026, 9, 22))

        assertNull(alarm.withoutPastDate(wednesdayNoon).date)
        assertEquals(LocalDateTime.of(2026, 9, 24, 7, 0), alarm.withoutPastDate(wednesdayNoon).nextTriggerAt(wednesdayNoon))
    }

    @Test
    fun `майбутня дата лишається`() {
        val alarm = Alarm(hour = 7, minute = 0, date = LocalDate.of(2026, 9, 24))

        assertEquals(alarm, alarm.withoutPastDate(wednesdayNoon))
    }

    @Test
    fun `дата зберігається як ISO, а старий JSON без дати читається`() {
        val json = Json { ignoreUnknownKeys = true }
        val alarm = Alarm(id = 1, hour = 7, minute = 0, date = LocalDate.of(2026, 10, 9))

        val encoded = json.encodeToString(Alarm.serializer(), alarm)
        assertTrue(encoded, "\"2026-10-09\"" in encoded)
        assertEquals(alarm, json.decodeFromString(Alarm.serializer(), encoded))
        assertNull(json.decodeFromString(Alarm.serializer(), """{"id":1,"hour":7,"minute":0}""").date)
    }
}
