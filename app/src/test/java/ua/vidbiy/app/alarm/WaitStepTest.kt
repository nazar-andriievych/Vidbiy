package ua.vidbiy.app.alarm

import org.junit.Assert.assertEquals
import org.junit.Test

class WaitStepTest {

    private val now = 10_000_000L
    private val minute = 60_000L

    @Test
    fun `тривоги немає в момент будильника — дзвонимо одразу, навіть з паузою`() {
        assertEquals(
            WaitStep.Ring(RingDecision.RING_CLEAR),
            nextWaitStep(RingDecision.RING_CLEAR, sawAlert = false, allClearAtElapsed = null, pauseMinutes = 5, nowElapsed = now),
        )
    }

    @Test
    fun `тривога триває — чекаємо`() {
        assertEquals(
            WaitStep.Wait(null),
            nextWaitStep(RingDecision.KEEP_WAITING, sawAlert = true, allClearAtElapsed = null, pauseMinutes = 5, nowElapsed = now),
        )
    }

    @Test
    fun `відбій без паузи — дзвонимо`() {
        assertEquals(
            WaitStep.Ring(RingDecision.RING_CLEAR),
            nextWaitStep(RingDecision.RING_CLEAR, sawAlert = true, allClearAtElapsed = null, pauseMinutes = 0, nowElapsed = now),
        )
    }

    @Test
    fun `відбій з паузою — починаємо паузу з цієї миті`() {
        assertEquals(
            WaitStep.Wait(now),
            nextWaitStep(RingDecision.RING_CLEAR, sawAlert = true, allClearAtElapsed = null, pauseMinutes = 5, nowElapsed = now),
        )
    }

    @Test
    fun `пауза ще не минула — чекаємо, відлік не зсувається`() {
        val since = now - 4 * minute
        assertEquals(
            WaitStep.Wait(since),
            nextWaitStep(RingDecision.RING_CLEAR, sawAlert = true, allClearAtElapsed = since, pauseMinutes = 5, nowElapsed = now),
        )
    }

    @Test
    fun `пауза минула — дзвонимо`() {
        assertEquals(
            WaitStep.Ring(RingDecision.RING_CLEAR),
            nextWaitStep(RingDecision.RING_CLEAR, sawAlert = true, allClearAtElapsed = now - 5 * minute, pauseMinutes = 5, nowElapsed = now),
        )
    }

    @Test
    fun `тривога повернулася під час паузи — пауза скидається`() {
        assertEquals(
            WaitStep.Wait(null),
            nextWaitStep(RingDecision.KEEP_WAITING, sawAlert = true, allClearAtElapsed = now - 2 * minute, pauseMinutes = 5, nowElapsed = now),
        )
    }

    @Test
    fun `застарілі дані під час паузи — дзвонимо, fail-safe`() {
        assertEquals(
            WaitStep.Ring(RingDecision.RING_STALE),
            nextWaitStep(RingDecision.RING_STALE, sawAlert = true, allClearAtElapsed = now - minute, pauseMinutes = 30, nowElapsed = now),
        )
    }

    @Test
    fun `крайній час під час паузи — дзвонимо`() {
        assertEquals(
            WaitStep.Ring(RingDecision.RING_DEADLINE),
            nextWaitStep(RingDecision.RING_DEADLINE, sawAlert = true, allClearAtElapsed = now - minute, pauseMinutes = 30, nowElapsed = now),
        )
    }
}
