package ua.vidbiy.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.vidbiy.app.data.Alarm
import ua.vidbiy.app.data.Place
import ua.vidbiy.app.data.SelectedRegion
import ua.vidbiy.app.data.WaitFor

class AlarmEditRulesTest {

    private val bucha = SelectedRegion("702", "Буча", coveringUids = setOf("702", "75", "14"))
    private val home = Place(3, "Дім", bucha)
    private val saved = Alarm(id = 1, hour = 7, minute = 0, region = bucha, placeId = 3, pauseMinutes = 5)

    @Test
    fun `новий будильник — стандартні значення й основне місце (FR-7, FR-26)`() {
        val draft = newAlarmDraft(home)

        assertEquals(Alarm.NEW_ID, draft.id)
        assertEquals(bucha, draft.region)
        assertEquals(3L, draft.placeId)
        assertTrue(draft.respectAlerts)
        assertEquals(WaitFor.RED_AND_YELLOW, draft.waitFor)
        assertEquals(0, draft.pauseMinutes)
        assertNull(draft.deadlineMinute)
        assertTrue(draft.days.isEmpty())
        assertNull(draft.date)
    }

    @Test
    fun `місць ще немає — новий будильник без регіону`() {
        assertNull(newAlarmDraft(null).region)
    }

    @Test
    fun `збереження без змін не припиняє очікування (FR-7b)`() {
        assertFalse(draftStopsWaiting(saved, saved, isWaiting = true))
    }

    @Test
    fun `змінений будильник, що чекає, — очікування припиниться`() {
        assertTrue(draftStopsWaiting(saved.copy(hour = 8), saved, isWaiting = true))
        assertTrue(draftStopsWaiting(saved.copy(pauseMinutes = 0), saved, isWaiting = true))
    }

    @Test
    fun `перемикач «увімкнено» не рахується за зміну — «Зберегти» завжди вмикає`() {
        assertFalse(draftStopsWaiting(saved.copy(enabled = true), saved.copy(enabled = false), isWaiting = true))
    }

    @Test
    fun `будильник, що не чекає, або новий — діалогу немає`() {
        assertFalse(draftStopsWaiting(saved.copy(hour = 8), saved, isWaiting = false))
        assertFalse(draftStopsWaiting(saved.copy(hour = 8), saved = null, isWaiting = true))
    }
}
