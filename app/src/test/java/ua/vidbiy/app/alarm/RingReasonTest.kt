package ua.vidbiy.app.alarm

import org.junit.Assert.assertEquals
import org.junit.Test
import ua.vidbiy.app.data.AlertLevel

class RingReasonTest {

    private val now = 1_790_000_000_000L

    private fun reason(
        decision: RingDecision,
        sawAlert: Boolean,
        level: AlertLevel? = null,
        allClearAt: Long? = null,
    ) = ringReasonFor(decision, sawAlert, "Дім", level, allClearAt, pauseMinutes = 5, deadlineMillis = null, nowMillis = now)

    @Test
    fun `відбій після очікування — «Відбій тривоги» з часом відбою`() {
        val r = reason(RingDecision.RING_CLEAR, sawAlert = true, allClearAt = now - 300_000)
        assertEquals(RingReason.Kind.ALL_CLEAR, r.kind)
        assertEquals(now - 300_000, r.allClearAtMillis)
        assertEquals(5, r.pauseMinutes)
    }

    @Test
    fun `тривоги не було — «Тривоги немає»`() {
        assertEquals(RingReason.Kind.NO_ALERT, reason(RingDecision.RING_CLEAR, sawAlert = false).kind)
    }

    @Test
    fun `«Лише червона» при жовтій — «Тривоги немає» несе жовтий рівень`() {
        val r = ringReasonFor(
            RingDecision.RING_CLEAR, sawAlert = false, "Дім", level = null, allClearAtMillis = null,
            pauseMinutes = 0, deadlineMillis = null, nowMillis = now, onlyYellow = true,
        )
        assertEquals(RingReason.Kind.NO_ALERT, r.kind)
        assertEquals(AlertLevel.YELLOW, r.level)
        assertEquals(null, reason(RingDecision.RING_CLEAR, sawAlert = false).level)
    }

    @Test
    fun `немає даних на старті — «Немає зв'язку», під час очікування — застарілі дані`() {
        assertEquals(RingReason.Kind.NO_CONNECTION, reason(RingDecision.RING_NO_DATA, sawAlert = false).kind)
        assertEquals(RingReason.Kind.NO_CONNECTION, reason(RingDecision.RING_STALE, sawAlert = false).kind)
        assertEquals(RingReason.Kind.STALE, reason(RingDecision.RING_STALE, sawAlert = true).kind)
    }

    @Test
    fun `крайній час і тривога понад добу несуть рівень для чипа`() {
        assertEquals(AlertLevel.RED, reason(RingDecision.RING_DEADLINE, true, AlertLevel.RED).level)
        val tooLong = reason(RingDecision.RING_ALERT_TOO_LONG, true, AlertLevel.YELLOW)
        assertEquals(RingReason.Kind.TOO_LONG, tooLong.kind)
        assertEquals(AlertLevel.YELLOW, tooLong.level)
    }
}
