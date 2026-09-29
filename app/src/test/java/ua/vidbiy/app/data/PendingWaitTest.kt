package ua.vidbiy.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingWaitTest {

    @Test
    fun snapshotSurvivesJsonRoundTrip() {
        val wait = PendingWait(
            alarmId = 3,
            deadlineMillis = 1_000L,
            startedAtMillis = 500L,
            alarm = Alarm(id = 3, hour = 7, minute = 0, waitFor = WaitFor.RED_ONLY, pauseMinutes = 5, placeId = 1),
        )

        assertEquals(wait, PendingWait.fromJson(wait.toJson()))
    }

    @Test
    fun recordFromOlderVersionHasNoSnapshot() {
        val old = PendingWait.fromJson("""{"alarmId":3,"deadlineMillis":1000,"startedAtMillis":500}""")

        assertEquals(3L, old?.alarmId)
        assertNull(old?.alarm)
    }
}
