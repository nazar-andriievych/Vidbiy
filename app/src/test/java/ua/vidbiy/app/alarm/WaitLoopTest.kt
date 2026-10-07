package ua.vidbiy.app.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.vidbiy.app.data.ActiveLevel
import ua.vidbiy.app.data.Alarm
import ua.vidbiy.app.data.AlertLevel
import ua.vidbiy.app.data.AlertsSnapshot
import ua.vidbiy.app.data.SelectedRegion
import ua.vidbiy.app.data.WaitFor

/**
 * Цикл очікування по кроках, з вигаданим годинником: так перевіряються сценарії розділу Б
 * docs/testing.md за мілісекунди, а не хвилинами на телефоні.
 */
class WaitLoopTest {

    private val bucha = SelectedRegion("702", "Буча", coveringUids = setOf("702", "75", "14"))
    private val alarm = Alarm(id = 1, hour = 7, minute = 0, region = bucha)

    private val second = 1_000L
    private val minute = 60 * second
    private val hour = 60 * minute

    /** Монотонний лічильник на старті очікування. */
    private val start = 5_000_000L

    /** Годинник на старті очікування. */
    private val startMillis = 1_790_000_000_000L

    private fun red(sinceMillis: Long = startMillis - hour) = mapOf("75" to listOf(ActiveLevel(AlertLevel.RED, sinceMillis, null)))
    private fun yellow() = mapOf("75" to listOf(ActiveLevel(AlertLevel.YELLOW, startMillis - hour, null)))
    private val clear = emptyMap<String, List<ActiveLevel>>()

    private fun answer(alerts: Map<String, List<ActiveLevel>>?, at: Long, ageSeconds: Long? = 10) =
        AlertsSnapshot(alerts = alerts, ageSeconds = if (alerts == null) null else ageSeconds, receivedAtElapsed = start + at)

    private fun failed(at: Long) = AlertsSnapshot.unavailable(start + at)

    /** Один крок через [at] мс після старту очікування. */
    private fun tick(
        state: WaitLoopState,
        fetched: AlertsSnapshot,
        at: Long,
        alarm: Alarm = this.alarm,
        deadlineMillis: Long? = null,
        giveUpAtMillis: Long = startMillis + 24 * hour,
    ) = waitTick(
        state = state,
        fetched = fetched,
        alarm = alarm,
        deadlineMillis = deadlineMillis,
        giveUpAtMillis = giveUpAtMillis,
        startedElapsed = start,
        nowElapsed = start + at,
        nowMillis = startMillis + at,
    )

    private fun waiting(at: Long = 0, alarm: Alarm = this.alarm) = tick(WaitLoopState(), answer(red(), at), at, alarm).state

    // --- Момент будильника (FR-8 … FR-11) ---

    @Test
    fun `тривоги немає — дзвонимо одразу, тривоги не бачили`() {
        val t = tick(WaitLoopState(), answer(clear, 0), 0)

        assertEquals(WaitAction.Ring(RingDecision.RING_CLEAR), t.action)
        assertFalse(t.state.sawAlert)
        assertEquals("ring", t.logStep)
    }

    @Test
    fun `тривога — чекаємо й опитуємо кожні 30 с`() {
        val t = tick(WaitLoopState(), answer(red(), 0), 0)

        assertEquals(WaitAction.Wait(POLL_INTERVAL_MILLIS), t.action)
        assertTrue(t.state.sawAlert)
        assertEquals("wait", t.logStep)
    }

    @Test
    fun `немає зв'язку на старті — повторюємо до 30 с, а не дзвонимо одразу`() {
        val first = tick(WaitLoopState(), failed(0), 0)
        val later = tick(first.state, failed(29 * second), 29 * second)

        assertEquals(WaitAction.Retry, first.action)
        assertEquals("retry", first.logStep)
        assertEquals(WaitAction.Retry, later.action)
    }

    @Test
    fun `за 30 с даних немає — дзвонимо «Немає зв'язку» (FR-9)`() {
        val t = tick(WaitLoopState(), failed(30 * second), 30 * second)

        assertEquals(WaitAction.Ring(RingDecision.RING_NO_DATA), t.action)
        assertFalse(t.state.sawAlert)
    }

    @Test
    fun `застарілі дані на старті — теж повтор, а потім дзвінок`() {
        val stale = answer(red(), 0, ageSeconds = MAX_DATA_AGE_SECONDS + 1)

        assertEquals(WaitAction.Retry, tick(WaitLoopState(), stale, 0).action)
        assertEquals(
            WaitAction.Ring(RingDecision.RING_STALE),
            tick(WaitLoopState(), answer(red(), 30 * second, MAX_DATA_AGE_SECONDS + 1), 30 * second).action,
        )
    }

    @Test
    fun `мережа з'явилася посеред вікна — рішення за свіжими даними`() {
        val retry = tick(WaitLoopState(), failed(0), 0).state
        val t = tick(retry, answer(red(), 10 * second), 10 * second)

        assertEquals(WaitAction.Wait(POLL_INTERVAL_MILLIS), t.action)
    }

    @Test
    fun `тривога понад добу на старті — дзвонимо (FR-17, FR-27)`() {
        val t = tick(WaitLoopState(), answer(red(sinceMillis = startMillis - 25 * hour), 0), 0)
        assertEquals(WaitAction.Ring(RingDecision.RING_ALERT_TOO_LONG), t.action)
    }

    // --- Очікування й відбій (FR-12 … FR-17) ---

    @Test
    fun `відбій без паузи — дзвонимо, і причина знає, що тривога була`() {
        val t = tick(waiting(), answer(clear, 30 * second), 30 * second)

        assertEquals(WaitAction.Ring(RingDecision.RING_CLEAR), t.action)
        // Причину будує служба зі стану до цього кроку: тривогу бачили — це «Відбій», а не «Тривоги немає».
        assertTrue(t.state.sawAlert)
    }

    @Test
    fun `відбій з паузою 2 хв — пауза з часом відбою, далі дзвінок`() {
        val alarm = alarm.copy(pauseMinutes = 2)
        val pause = tick(waiting(alarm = alarm), answer(clear, 30 * second), 30 * second, alarm)

        assertEquals(WaitAction.Wait(POLL_INTERVAL_MILLIS), pause.action)
        assertEquals("pause", pause.logStep)
        assertEquals(startMillis + 30 * second, pause.state.allClearAtMillis)

        val stillPausing = tick(pause.state, answer(clear, 2 * minute), 2 * minute, alarm)
        // До кінця паузи 30 с — прокидаємося саме тоді, а не за звичайним розкладом.
        assertEquals(WaitAction.Wait(30 * second), stillPausing.action)
        assertEquals(startMillis + 30 * second, stillPausing.state.allClearAtMillis)

        val ring = tick(stillPausing.state, answer(clear, 2 * minute + 30 * second), 2 * minute + 30 * second, alarm)
        assertEquals(WaitAction.Ring(RingDecision.RING_CLEAR), ring.action)
        assertEquals(startMillis + 30 * second, ring.state.allClearAtMillis)
    }

    @Test
    fun `пауза ближча за 30 с — будимося рівно до її кінця`() {
        val alarm = alarm.copy(pauseMinutes = 2)
        val pause = tick(waiting(alarm = alarm), answer(clear, 30 * second), 30 * second, alarm).state

        val t = tick(pause, answer(clear, 2 * minute + 20 * second), 2 * minute + 20 * second, alarm)

        assertEquals(WaitAction.Wait(10 * second), t.action)
    }

    @Test
    fun `тривога повернулася під час паузи — пауза скидається (FR-14)`() {
        val alarm = alarm.copy(pauseMinutes = 2)
        val pause = tick(waiting(alarm = alarm), answer(clear, 30 * second), 30 * second, alarm).state

        val back = tick(pause, answer(red(), 90 * second), 90 * second, alarm)

        assertEquals(WaitAction.Wait(POLL_INTERVAL_MILLIS), back.action)
        assertNull(back.state.allClearAtElapsed)
        assertNull(back.state.allClearAtMillis)
    }

    @Test
    fun `лише червона — червона змінилася на жовту, це відбій (FR-13)`() {
        val alarm = alarm.copy(waitFor = WaitFor.RED_ONLY)
        val t = tick(waiting(alarm = alarm), answer(yellow(), 30 * second), 30 * second, alarm)

        assertEquals(WaitAction.Ring(RingDecision.RING_CLEAR), t.action)
    }

    @Test
    fun `окрема невдала спроба нічого не змінює (FR-15)`() {
        val t = tick(waiting(), failed(30 * second), 30 * second)

        assertEquals(WaitAction.Wait(POLL_INTERVAL_MILLIS), t.action)
        assertEquals(RingDecision.KEEP_WAITING, t.decision)
    }

    @Test
    fun `дані старіють понад 3 хв — дзвонимо одразу, без стартового повтору`() {
        // Остання відповідь мала 10 с; ще 171 с без зв'язку — разом понад 180 с.
        val t = tick(waiting(), failed(171 * second), 171 * second)

        assertEquals(WaitAction.Ring(RingDecision.RING_STALE), t.action)
    }

    @Test
    fun `застарілі дані в перші 30 с очікування теж дзвонять, якщо тривогу вже бачили`() {
        val sawAlert = tick(WaitLoopState(), answer(red(), 0, ageSeconds = 170), 0).state

        val t = tick(sawAlert, failed(20 * second), 20 * second)

        assertEquals(WaitAction.Ring(RingDecision.RING_STALE), t.action)
    }

    @Test
    fun `настав крайній час — дзвонимо, хоча тривога триває (FR-16)`() {
        val t = tick(waiting(), answer(red(), 3 * minute), 3 * minute, deadlineMillis = startMillis + 3 * minute)

        assertEquals(WaitAction.Ring(RingDecision.RING_DEADLINE), t.action)
    }

    @Test
    fun `тривога досягла доби під час очікування — дзвонимо (FR-17)`() {
        val since = startMillis - 24 * hour + 2 * minute
        val state = tick(WaitLoopState(), answer(red(since), 0), 0).state

        val t = tick(state, answer(red(since), 2 * minute), 2 * minute)

        assertEquals(WaitAction.Ring(RingDecision.RING_ALERT_TOO_LONG), t.action)
    }

    @Test
    fun `доба очікування — остання страховка, навіть якщо тривогу оголошували знову`() {
        val fresh = red(sinceMillis = startMillis + 23 * hour)

        val t = tick(waiting(), answer(fresh, 24 * hour), 24 * hour, giveUpAtMillis = startMillis + 24 * hour)

        assertEquals(WaitAction.Ring(RingDecision.RING_ALERT_TOO_LONG), t.action)
    }

    @Test
    fun `регіон не обрано — чекати нема на що, дзвонимо`() {
        val noRegion = alarm.copy(region = null)
        val t = tick(WaitLoopState(), answer(red(), 30 * second), 30 * second, noRegion)

        assertEquals(WaitAction.Ring(RingDecision.RING_NO_DATA), t.action)
    }

    // --- Застаріла версія застосунку (docs/proxy-api.md, «Оновлення застосунку») ---

    @Test
    fun `застаріла версія на старті — дзвонимо одразу, хоч тривога й триває`() {
        val t = tick(WaitLoopState(), answer(red(), 0).copy(outdated = true), 0)

        assertEquals(WaitAction.Ring(RingDecision.RING_OUTDATED), t.action)
    }

    @Test
    fun `версія застаріла посеред очікування — дзвонимо, не чекаючи відбою`() {
        val t = tick(waiting(), answer(red(), 30 * second).copy(outdated = true), 30 * second)

        assertEquals(WaitAction.Ring(RingDecision.RING_OUTDATED), t.action)
    }

    @Test
    fun `застарілість видно навіть у відповіді «даних немає» — попередня відома її не перекриває`() {
        val unknown = AlertsSnapshot(alerts = null, ageSeconds = null, receivedAtElapsed = start + 30 * second, outdated = true)
        val t = tick(waiting(), unknown, 30 * second)

        assertEquals(WaitAction.Ring(RingDecision.RING_OUTDATED), t.action)
    }
}
