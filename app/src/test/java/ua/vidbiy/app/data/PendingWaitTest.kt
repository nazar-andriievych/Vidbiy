package ua.vidbiy.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingWaitTest {

    private val start = 1_790_000_000_000L
    private val hour = 60 * 60_000L

    @Test
    fun `знімок налаштувань переживає запис у JSON`() {
        val wait = PendingWait(
            alarmId = 3,
            deadlineMillis = 1_000L,
            startedAtMillis = 500L,
            alarm = Alarm(id = 3, hour = 7, minute = 0, waitFor = WaitFor.RED_ONLY, pauseMinutes = 5, placeId = 1),
        )

        assertEquals(wait, PendingWait.fromJson(wait.toJson()))
    }

    @Test
    fun `запис зі старої версії читається без знімка`() {
        val old = PendingWait.fromJson("""{"alarmId":3,"deadlineMillis":1000,"startedAtMillis":500}""")

        assertEquals(3L, old?.alarmId)
        assertNull(old?.alarm)
    }

    @Test
    fun `без крайнього часу будильник здається через добу очікування`() {
        assertEquals(start + 24 * hour, PendingWait(1, startedAtMillis = start).giveUpAtMillis(start))
    }

    @Test
    fun `крайній час раніше за добу — здається в крайній час`() {
        assertEquals(start + 2 * hour, PendingWait(1, deadlineMillis = start + 2 * hour, startedAtMillis = start).giveUpAtMillis(start))
    }

    @Test
    fun `крайній час пізніше за добу — однаково не довше доби`() {
        assertEquals(start + 24 * hour, PendingWait(1, deadlineMillis = start + 30 * hour, startedAtMillis = start).giveUpAtMillis(start))
    }

    @Test
    fun `старий запис без початку отримує його один раз — доба не відсувається щоопитування`() {
        val legacy = PendingWait(1, startedAtMillis = 0)

        val fixed = legacy.withKnownStart(start)

        assertEquals(start, fixed.startedAtMillis)
        // Через 23 год межа та сама: рахується від зафіксованого початку, а не від «зараз».
        assertEquals(start + 24 * hour, fixed.giveUpAtMillis(start + 23 * hour))
    }

    @Test
    fun `відомий початок не переписується`() {
        val wait = PendingWait(1, startedAtMillis = start)
        assertEquals(wait, wait.withKnownStart(start + hour))
    }
}
