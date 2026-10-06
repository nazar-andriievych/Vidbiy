package ua.vidbiy.app.alarm

import org.junit.Assert.assertEquals
import org.junit.Test
import ua.vidbiy.app.data.ActiveLevel
import ua.vidbiy.app.data.AlertLevel
import ua.vidbiy.app.data.AlertsSnapshot
import ua.vidbiy.app.data.SelectedRegion
import ua.vidbiy.app.data.WaitFor

class RingDecisionTest {

    private val region = SelectedRegion(
        uid = "225",
        title = "Луцька громада",
        path = "Волинська область · Луцький район",
        coveringUids = setOf("225", "39", "8"),
    )

    /** Момент «зараз» за монотонним лічильником; саме в цей час прийшла відповідь. */
    private val now = 1_000_000L

    /** «Зараз» за годинником — для правила 24 год. */
    private val nowMillis = 1_790_000_000_000L
    private val hour = 60 * 60 * 1000L

    private fun red(sinceHoursAgo: Long = 1) = ActiveLevel(AlertLevel.RED, nowMillis - sinceHoursAgo * hour, null)
    private fun yellow(sinceHoursAgo: Long = 1) = ActiveLevel(AlertLevel.YELLOW, nowMillis - sinceHoursAgo * hour, null)

    private fun snapshot(
        alerts: Map<String, List<ActiveLevel>>?,
        ageSeconds: Long? = 5,
        received: Long = now,
    ) = AlertsSnapshot(alerts = alerts, ageSeconds = ageSeconds, receivedAtElapsed = received)

    private fun decide(
        snapshot: AlertsSnapshot?,
        waitFor: WaitFor = WaitFor.RED_AND_YELLOW,
        pastDeadline: Boolean = false,
        region: SelectedRegion? = this.region,
    ) = decideRing(snapshot, now, nowMillis, region, waitFor, pastDeadline)

    @Test
    fun `тривога в області — чекаємо відбою`() {
        assertEquals(RingDecision.KEEP_WAITING, decide(snapshot(mapOf("8" to listOf(red())))))
    }

    @Test
    fun `тривог немає — дзвонимо`() {
        assertEquals(RingDecision.RING_CLEAR, decide(snapshot(emptyMap())))
    }

    @Test
    fun `тривога в чужій області не заважає`() {
        assertEquals(RingDecision.RING_CLEAR, decide(snapshot(mapOf("16" to listOf(red())))))
    }

    @Test
    fun `жовта тривога — чекаємо, якщо зважаємо на обидва рівні`() {
        assertEquals(RingDecision.KEEP_WAITING, decide(snapshot(mapOf("8" to listOf(yellow())))))
    }

    @Test
    fun `лише червона — жовта тривога не заважає дзвонити`() {
        val decision = decide(snapshot(mapOf("8" to listOf(yellow()))), waitFor = WaitFor.RED_ONLY)

        assertEquals(RingDecision.RING_CLEAR, decision)
    }

    @Test
    fun `лише червона — чекаємо, поки є хоч одна червона серед регіонів, що покривають`() {
        // Жовта в області, червона в районі: найвищий рівень для громади — червоний.
        val alerts = mapOf("8" to listOf(yellow()), "39" to listOf(red()))

        assertEquals(RingDecision.KEEP_WAITING, decide(snapshot(alerts), waitFor = WaitFor.RED_ONLY))
    }

    @Test
    fun `лише червона — червона змінилася на жовту, це відбій`() {
        val redAndYellow = snapshot(mapOf("39" to listOf(red(), yellow())))
        val onlyYellow = snapshot(mapOf("39" to listOf(yellow())))

        assertEquals(RingDecision.KEEP_WAITING, decide(redAndYellow, waitFor = WaitFor.RED_ONLY))
        assertEquals(RingDecision.RING_CLEAR, decide(onlyYellow, waitFor = WaitFor.RED_ONLY))
    }

    @Test
    fun `тривога понад добу не рахується`() {
        val decision = decide(snapshot(mapOf("8" to listOf(red(sinceHoursAgo = 25)))))

        assertEquals(RingDecision.RING_ALERT_TOO_LONG, decision)
    }

    @Test
    fun `давня тривога не заважає свіжій у тому ж регіоні`() {
        // Жовта в області триває 10 днів, але щойно оголосили червону — чекаємо.
        val alerts = mapOf("8" to listOf(yellow(sinceHoursAgo = 240)), "39" to listOf(red(sinceHoursAgo = 1)))

        assertEquals(RingDecision.KEEP_WAITING, decide(snapshot(alerts)))
    }

    @Test
    fun `лише червона — червона понад добу не рахується, хоч поруч свіжа жовта`() {
        // Жовта не важить для «лише червона», а червона триває вже добу (FR-27): дзвонимо.
        val alerts = mapOf("8" to listOf(red(sinceHoursAgo = 25)), "39" to listOf(yellow(sinceHoursAgo = 1)))

        assertEquals(RingDecision.RING_ALERT_TOO_LONG, decide(snapshot(alerts), waitFor = WaitFor.RED_ONLY))
    }

    @Test
    fun `проксі віддав тривоги без віку даних — свіжість невідома, дзвонимо`() {
        assertEquals(RingDecision.RING_NO_DATA, decide(snapshot(mapOf("8" to listOf(red())), ageSeconds = null)))
    }

    @Test
    fun `час початку тривоги в майбутньому (годинник телефона відстає) — тривога рахується`() {
        val alerts = mapOf("8" to listOf(ActiveLevel(AlertLevel.RED, nowMillis + 5 * 60_000, null)))

        assertEquals(RingDecision.KEEP_WAITING, decide(snapshot(alerts)))
    }

    @Test
    fun `крайній час сильніший за тривогу`() {
        assertEquals(
            RingDecision.RING_DEADLINE,
            decide(snapshot(mapOf("8" to listOf(red()))), pastDeadline = true),
        )
    }

    @Test
    fun `сервер не відповів — дзвонимо за fail-safe`() {
        assertEquals(RingDecision.RING_NO_DATA, decide(AlertsSnapshot.unavailable(now)))
    }

    @Test
    fun `проксі не має даних від ukrainealarm — дзвонимо`() {
        assertEquals(RingDecision.RING_NO_DATA, decide(snapshot(alerts = null)))
    }

    @Test
    fun `дані старші за три хвилини — дзвонимо, навіть якщо в них тривога`() {
        val stale = snapshot(mapOf("8" to listOf(red())), ageSeconds = MAX_DATA_AGE_SECONDS + 1)

        assertEquals(RingDecision.RING_STALE, decide(stale))
    }

    @Test
    fun `вік рахується разом із часом очікування на телефоні`() {
        // Відповідь прийшла свіжою (20 с), але відтоді телефон чекав ще три хвилини.
        val aging = snapshot(mapOf("8" to listOf(red())), ageSeconds = 20, received = now - 170_000)

        assertEquals(RingDecision.RING_STALE, decide(aging))
    }

    @Test
    fun `свіжі дані з тим самим віком на межі ще годяться`() {
        val borderline = snapshot(mapOf("8" to listOf(red())), ageSeconds = MAX_DATA_AGE_SECONDS)

        assertEquals(RingDecision.KEEP_WAITING, decide(borderline))
    }

    @Test
    fun `регіон не обрано — чекати нема на що`() {
        assertEquals(RingDecision.RING_NO_DATA, decide(snapshot(mapOf("8" to listOf(red()))), region = null))
    }

    @Test
    fun `невдала спроба не затирає останні відомі дані`() {
        val known = snapshot(mapOf("8" to listOf(red())), received = now - 60_000)
        val failed = AlertsSnapshot.unavailable(now)

        val kept = failed.orPrevious(known)

        // FR-15: одна невдача — ще не привід дзвонити; дані просто старіють.
        assertEquals(known, kept)
        assertEquals(RingDecision.KEEP_WAITING, decide(kept))
    }

    @Test
    fun `старі дані після кількох невдач стають застарілими — дзвонимо`() {
        val known = snapshot(mapOf("8" to listOf(red())), ageSeconds = 20, received = now - 170_000)

        assertEquals(RingDecision.RING_STALE, decide(AlertsSnapshot.unavailable(now).orPrevious(known)))
    }

    @Test
    fun `свіжа відповідь завжди замінює попередню`() {
        val known = snapshot(mapOf("8" to listOf(red())), received = now - 60_000)
        val fresh = snapshot(emptyMap())

        assertEquals(RingDecision.RING_CLEAR, decide(fresh.orPrevious(known)))
    }
}
