package ua.vidbiy.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ua.vidbiy.app.R
import ua.vidbiy.app.alarm.RingReason

/** FR-21, FR-24: заголовок блоку причини на екрані дзвінка. */
class RingReasonTitleTest {

    @Test
    fun `звичайний будильник — без блоку причини`() {
        assertNull(reasonTitleRes(RingReason.Plain))
    }

    @Test
    fun `тривоги не було — «Тривоги немає», а не «Звичайний час»`() {
        assertEquals(R.string.ring_no_alert_title, reasonTitleRes(RingReason(RingReason.Kind.NO_ALERT)))
    }

    @Test
    fun `застарілі дані в будильника — «Немає зв'язку», у разового режиму — «Немає даних»`() {
        assertEquals(R.string.ring_no_connection_title, reasonTitleRes(RingReason(RingReason.Kind.STALE)))
        assertEquals(R.string.ring_no_data_title, reasonTitleRes(RingReason(RingReason.Kind.STALE, oneShot = true)))
    }

    @Test
    fun `кожна причина, крім звичайної, має заголовок`() {
        RingReason.Kind.entries.filter { it != RingReason.Kind.PLAIN }.forEach { kind ->
            assertEquals(kind.name, true, reasonTitleRes(RingReason(kind)) != null)
        }
    }
}
