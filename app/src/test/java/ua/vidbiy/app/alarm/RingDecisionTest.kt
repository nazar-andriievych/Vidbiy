package ua.vidbiy.app.alarm

import org.junit.Assert.assertEquals
import org.junit.Test
import ua.vidbiy.app.data.AlertsSnapshot
import ua.vidbiy.app.data.SelectedRegion

class RingDecisionTest {

    private val region = SelectedRegion(
        uid = "225",
        title = "Луцька громада",
        path = "Волинська область · Луцький район",
        coveringUids = setOf("225", "39", "8"),
    )

    /** Момент «зараз» за монотонним лічильником; саме в цей час прийшла відповідь. */
    private val now = 1_000_000L

    private fun snapshot(uids: Set<String>?, ageSeconds: Long? = 5, received: Long = now) =
        AlertsSnapshot(alertUids = uids, ageSeconds = ageSeconds, receivedAtElapsed = received)

    @Test
    fun `тривога в області — чекаємо відбою`() {
        val decision = decideRing(snapshot(setOf("8")), now, region, pastDeadline = false)

        assertEquals(RingDecision.KEEP_WAITING, decision)
    }

    @Test
    fun `тривог немає — дзвонимо`() {
        val decision = decideRing(snapshot(emptySet()), now, region, pastDeadline = false)

        assertEquals(RingDecision.RING_CLEAR, decision)
    }

    @Test
    fun `тривога в чужій області не заважає`() {
        val decision = decideRing(snapshot(setOf("16")), now, region, pastDeadline = false)

        assertEquals(RingDecision.RING_CLEAR, decision)
    }

    @Test
    fun `крайній час сильніший за тривогу`() {
        val decision = decideRing(snapshot(setOf("8")), now, region, pastDeadline = true)

        assertEquals(RingDecision.RING_DEADLINE, decision)
    }

    @Test
    fun `сервер не відповів — дзвонимо за fail-safe`() {
        val decision = decideRing(
            AlertsSnapshot.unavailable(now),
            now,
            region,
            pastDeadline = false,
        )

        assertEquals(RingDecision.RING_NO_DATA, decision)
    }

    @Test
    fun `проксі не має даних від alerts in ua — дзвонимо`() {
        val decision = decideRing(snapshot(uids = null), now, region, pastDeadline = false)

        assertEquals(RingDecision.RING_NO_DATA, decision)
    }

    @Test
    fun `дані старші за три хвилини — дзвонимо, навіть якщо в них тривога`() {
        val stale = snapshot(setOf("8"), ageSeconds = MAX_DATA_AGE_SECONDS + 1)

        assertEquals(RingDecision.RING_STALE, decideRing(stale, now, region, pastDeadline = false))
    }

    @Test
    fun `вік рахується разом із часом очікування на телефоні`() {
        // Відповідь прийшла свіжою (20 с), але відтоді телефон чекав ще три хвилини.
        val received = now - 170_000
        val aging = snapshot(setOf("8"), ageSeconds = 20, received = received)

        assertEquals(RingDecision.RING_STALE, decideRing(aging, now, region, pastDeadline = false))
    }

    @Test
    fun `свіжі дані з тим самим віком на межі ще годяться`() {
        val borderline = snapshot(setOf("8"), ageSeconds = MAX_DATA_AGE_SECONDS)

        assertEquals(
            RingDecision.KEEP_WAITING,
            decideRing(borderline, now, region, pastDeadline = false),
        )
    }

    @Test
    fun `регіон не обрано — чекати нема на що`() {
        val decision = decideRing(snapshot(setOf("8")), now, region = null, pastDeadline = false)

        assertEquals(RingDecision.RING_NO_DATA, decision)
    }
}
