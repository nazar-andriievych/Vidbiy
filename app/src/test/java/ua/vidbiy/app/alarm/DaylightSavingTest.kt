package ua.vidbiy.app.alarm

import org.junit.Assert.assertEquals
import org.junit.Test
import ua.vidbiy.app.data.Alarm
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Переходи годинника в Україні. Восени 2026-10-25 о 04:00 літнього часу (UTC+3) стрілки
 * переводять на 03:00 зимового (UTC+2); навесні 2027-03-28 о 03:00 зимового — на 04:00 літнього.
 */
class DaylightSavingTest {

    private val kyiv = ZoneId.of("Europe/Kyiv")
    private val everyDay = setOf(1, 2, 3, 4, 5, 6, 7)

    private fun utc(text: String): Long = Instant.parse(text).toEpochMilli()

    @Test
    fun `ранковий будильник у ніч переходу на зимовий час дзвонить о 07_00 за новим часом`() {
        val alarm = Alarm(hour = 7, minute = 0, days = everyDay)
        val next = alarm.nextTriggerAt(LocalDateTime.of(2026, 10, 24, 7, 0, 5))!!

        assertEquals(LocalDateTime.of(2026, 10, 25, 7, 0), next)
        assertEquals(utc("2026-10-25T05:00:00Z"), next.toEpochMillis(kyiv))
    }

    @Test
    fun `ранковий будильник у ніч переходу на літній час дзвонить о 07_00 за новим часом`() {
        val alarm = Alarm(hour = 7, minute = 0, days = everyDay)
        val next = alarm.nextTriggerAt(LocalDateTime.of(2027, 3, 27, 7, 0, 5))!!

        assertEquals(utc("2027-03-28T04:00:00Z"), next.toEpochMillis(kyiv))
    }

    @Test
    fun `час, що восени трапляється двічі, береться першим`() {
        val alarm = Alarm(hour = 3, minute = 30, days = everyDay)
        val next = alarm.nextTriggerAt(LocalDateTime.of(2026, 10, 24, 12, 0))!!

        // 03:30 ще за літнім часом, тобто 00:30 UTC, а не 01:30.
        assertEquals(utc("2026-10-25T00:30:00Z"), next.toEpochMillis(kyiv))
    }

    @Test
    fun `після першого дзвінка о 03_30 восени наступний — завтра`() {
        val alarm = Alarm(hour = 3, minute = 30, days = everyDay)

        assertEquals(
            LocalDateTime.of(2026, 10, 26, 3, 30),
            alarm.nextTriggerAt(LocalDateTime.of(2026, 10, 25, 3, 30, 5)),
        )
    }

    @Test
    fun `час, якого навесні немає, зсувається на годину вперед`() {
        val alarm = Alarm(hour = 3, minute = 30, days = everyDay)
        val next = alarm.nextTriggerAt(LocalDateTime.of(2027, 3, 27, 12, 0))!!

        // 03:30 цієї ночі не існує: дзвінок о 04:30 літнього часу.
        assertEquals(utc("2027-03-28T01:30:00Z"), next.toEpochMillis(kyiv))
    }

    @Test
    fun `крайній час через перехід на зимовий час — за новим часом`() {
        // Будильник 23:00, крайній 08:00 наступного ранку; між ними стрілки переводять назад.
        val alarm = Alarm(hour = 23, minute = 0, deadlineMinute = 8 * 60)
        val deadline = alarm.deadlineAfter(LocalDateTime.of(2026, 10, 24, 23, 0))!!

        assertEquals(LocalDateTime.of(2026, 10, 25, 8, 0), deadline)
        assertEquals(utc("2026-10-25T06:00:00Z"), deadline.toEpochMillis(kyiv))
    }
}
