package ua.vidbiy.app.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ua.vidbiy.app.data.Alarm
import ua.vidbiy.app.data.SelectedRegion
import java.time.LocalDate

class FirePlanTest {

    private val now = 1_790_000_000_000L
    private val bucha = SelectedRegion("702", "Буча", coveringUids = setOf("702", "75", "14"))
    private val alarm = Alarm(id = 1, hour = 7, minute = 0, region = bucha)

    private fun action(kind: FireKind, alarm: Alarm = this.alarm, hasWait: Boolean = false, deadline: Long? = null) =
        fireAction(kind, alarm, hasWait, deadline, now)

    @Test
    fun `звичайний час із тривогами й регіоном — перевіряємо тривогу`() {
        assertEquals(FireAction.WaitForAllClear, action(FireKind.REGULAR))
    }

    @Test
    fun `«враховувати тривоги» вимкнено — дзвонить без блоку причини (FR-2, FR-21)`() {
        assertEquals(FireAction.Ring(RingReason.Kind.PLAIN), action(FireKind.REGULAR, alarm.copy(respectAlerts = false)))
    }

    @Test
    fun `регіону немає — звичайний будильник`() {
        assertEquals(FireAction.Ring(RingReason.Kind.PLAIN), action(FireKind.REGULAR, alarm.copy(region = null)))
    }

    @Test
    fun `відкладення дзвонить незалежно від тривоги (FR-20)`() {
        assertEquals(FireAction.Ring(RingReason.Kind.PLAIN), action(FireKind.SNOOZE, hasWait = true))
    }

    @Test
    fun `страховка в крайній час — «Настав крайній час»`() {
        assertEquals(FireAction.Ring(RingReason.Kind.DEADLINE), action(FireKind.DEADLINE, hasWait = true, deadline = now))
    }

    @Test
    fun `AlarmManager розбудив на пів хвилини раніше — це ще крайній час`() {
        assertEquals(RingReason.Kind.DEADLINE, backstopReason(now + 30_000, now))
        assertEquals(RingReason.Kind.TOO_LONG, backstopReason(now + 61_000, now))
    }

    @Test
    fun `страховка без крайнього часу — доба очікування (FR-17)`() {
        assertEquals(FireAction.Ring(RingReason.Kind.TOO_LONG), action(FireKind.DEADLINE, hasWait = true))
    }

    @Test
    fun `страховка, а очікування вже закінчилось — звичайний дзвінок, не тиша`() {
        assertEquals(FireAction.Ring(RingReason.Kind.PLAIN), action(FireKind.DEADLINE, hasWait = false, deadline = now))
    }

    @Test
    fun `одноразовий після спрацювання вимикається разом з датою (FR-1a)`() {
        val dated = alarm.copy(date = LocalDate.of(2026, 10, 9))

        val after = dated.afterRegularFire()!!

        assertEquals(false, after.enabled)
        assertNull(after.date)
    }

    @Test
    fun `повторюваний лишається — його ставлять на наступний раз`() {
        assertNull(alarm.copy(days = setOf(1, 2, 3)).afterRegularFire())
    }
}
