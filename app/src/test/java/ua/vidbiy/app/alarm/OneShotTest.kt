package ua.vidbiy.app.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ua.vidbiy.app.data.Place
import ua.vidbiy.app.data.SelectedRegion
import ua.vidbiy.app.data.WaitFor
import java.time.LocalTime

class OneShotTest {

    @Test
    fun `перевірка — тривога, немає тривоги, немає даних`() {
        assertEquals(OneShotCheck.ALERT, oneShotCheck(RingDecision.KEEP_WAITING))
        assertEquals(OneShotCheck.NO_ALERT, oneShotCheck(RingDecision.RING_CLEAR))
        assertEquals(OneShotCheck.NO_DATA, oneShotCheck(RingDecision.RING_NO_DATA))
        assertEquals(OneShotCheck.NO_DATA, oneShotCheck(RingDecision.RING_STALE))
    }

    @Test
    fun `тривога понад добу для режиму — тривоги немає`() {
        assertEquals(OneShotCheck.NO_ALERT, oneShotCheck(RingDecision.RING_ALERT_TOO_LONG))
    }

    @Test
    fun `віртуальний будильник — основне місце, налаштування режиму, без крайнього часу`() {
        val home = Place(7, "Дім", SelectedRegion("31", "м. Київ", coveringUids = setOf("31")))
        val alarm = OneShot.alarm(home, WaitFor.RED_ONLY, pauseMinutes = 5, startedAt = LocalTime.of(3, 12))
        assertEquals(OneShot.ONE_SHOT_ID, alarm.id)
        assertEquals(home.region, alarm.region)
        assertEquals(7L, alarm.placeId)
        assertEquals(WaitFor.RED_ONLY, alarm.waitFor)
        assertEquals(5, alarm.pauseMinutes)
        assertNull(alarm.deadlineMinute)
    }
}
