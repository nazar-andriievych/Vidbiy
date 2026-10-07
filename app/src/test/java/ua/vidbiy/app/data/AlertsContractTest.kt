package ua.vidbiy.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.vidbiy.app.alarm.RingDecision
import ua.vidbiy.app.alarm.decideRing
import java.io.File
import java.time.Instant

/**
 * Контракт `/v1/alerts` (docs/proxy-api.md): ту саму відповідь будує тест сервера
 * (server/test/contract.test.ts). Тут — що застосунок її розуміє й ухвалює правильне рішення.
 */
class AlertsContractTest {

    /** Gradle запускає тести з каталогу модуля `app/`. */
    private fun fixture(name: String) = File("../docs/fixtures/$name").readText()

    @Test
    fun `відома картина з сервера розбирається повністю`() {
        val snapshot = parseAlertsResponse(fixture("alerts-v1.json"), receivedAtElapsed = 0)

        assertEquals(5L, snapshot.ageSeconds)
        assertEquals("2026-10-06T07:01:00.000Z", snapshot.confirmedAt)
        assertEquals(setOf("14", "124"), snapshot.alerts!!.keys)
        assertEquals(
            listOf(
                ActiveLevel(AlertLevel.RED, Instant.parse("2026-10-06T07:01:00Z").toEpochMilli(), null),
                ActiveLevel(AlertLevel.YELLOW, Instant.parse("2026-10-06T06:00:00Z").toEpochMilli(), "Дронова загроза (жовтий рівень)"),
            ),
            snapshot.alerts!!["14"],
        )
    }

    @Test
    fun `тривога в області з відповіді сервера тримає будильник громади`() {
        val snapshot = parseAlertsResponse(fixture("alerts-v1.json"), receivedAtElapsed = 0, appVersionCode = 3)
        val bucha = SelectedRegion("702", "Буча", coveringUids = setOf("702", "75", "14"))
        val now = Instant.parse("2026-10-06T07:01:05Z").toEpochMilli()

        assertEquals(RingDecision.KEEP_WAITING, decideRing(snapshot, 0, now, bucha, WaitFor.RED_ONLY, pastDeadline = false))
    }

    @Test
    fun `невідомий стан сервера — дзвонимо за fail-safe`() {
        val snapshot = parseAlertsResponse(fixture("alerts-v1-unknown.json"), receivedAtElapsed = 0)

        assertNull(snapshot.alerts)
        assertFalse(snapshot.isKnown)
        val region = SelectedRegion("31", "м. Київ", coveringUids = setOf("31"))
        assertEquals(RingDecision.RING_NO_DATA, decideRing(snapshot, 0, 0, region, WaitFor.RED_AND_YELLOW, pastDeadline = false))
    }

    @Test
    fun `сервер каже про випуск — застосунок його розуміє`() {
        val snapshot = parseAlertsResponse(fixture("alerts-v1.json"), receivedAtElapsed = 0, appVersionCode = 2)

        assertEquals(AppUpdate(3, "1.2", minVersionCode = 2, url = "https://example.org/vidbiy"), snapshot.update)
        assertFalse(snapshot.outdated)
    }

    @Test
    fun `версія нижча за мінімальну — застаріла, будильник не чекає тривоги`() {
        val snapshot = parseAlertsResponse(fixture("alerts-v1.json"), receivedAtElapsed = 0, appVersionCode = 1)
        val bucha = SelectedRegion("702", "Буча", coveringUids = setOf("702", "75", "14"))
        val now = Instant.parse("2026-10-06T07:01:05Z").toEpochMilli()

        assertTrue(snapshot.outdated)
        assertEquals(RingDecision.RING_OUTDATED, decideRing(snapshot, 0, now, bucha, WaitFor.RED_ONLY, pastDeadline = false))
    }

    @Test
    fun `випуск не налаштовано — нічого не вимагаємо`() {
        val snapshot = parseAlertsResponse(fixture("alerts-v1-unknown.json"), receivedAtElapsed = 0, appVersionCode = 1)

        assertNull(snapshot.update)
        assertFalse(snapshot.outdated)
    }

    @Test
    fun `відповідь старого сервера без поля update теж розбирається`() {
        val snapshot = parseAlertsResponse("""{"v":1,"alerts":[],"confirmed_at":null,"age_seconds":3}""", receivedAtElapsed = 0)

        assertNull(snapshot.update)
        assertFalse(snapshot.outdated)
    }
}
