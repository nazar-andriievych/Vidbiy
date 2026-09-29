package ua.vidbiy.app.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.vidbiy.app.data.ActiveLevel
import ua.vidbiy.app.data.AlertLevel
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
    fun `лише червона, а діє жовта — окреме повідомлення`() {
        assertEquals(OneShotCheck.ONLY_YELLOW, oneShotCheck(RingDecision.RING_CLEAR, yellowActive = true))
        assertEquals(OneShotCheck.ALERT, oneShotCheck(RingDecision.KEEP_WAITING, yellowActive = true))
        assertEquals(OneShotCheck.NO_DATA, oneShotCheck(RingDecision.RING_STALE, yellowActive = true))
    }

    @Test
    fun `свіжа жовта — у районі громади, стара й сусідня не рахуються`() {
        val now = 1_000_000_000_000L
        val bucha = SelectedRegion("702", "Буча", coveringUids = setOf("702", "75", "14"))
        fun yellow(ageMillis: Long) = listOf(ActiveLevel(AlertLevel.YELLOW, now - ageMillis, null))
        assertTrue(bucha.hasFreshYellow(mapOf("75" to yellow(60_000)), now))
        assertFalse(bucha.hasFreshYellow(mapOf("75" to yellow(MAX_ALERT_AGE_MILLIS + 1)), now))
        assertFalse(bucha.hasFreshYellow(mapOf("76" to yellow(60_000)), now))
        assertFalse(bucha.hasFreshYellow(mapOf("75" to listOf(ActiveLevel(AlertLevel.RED, now, null))), now))
        assertFalse(bucha.hasFreshYellow(null, now))
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
