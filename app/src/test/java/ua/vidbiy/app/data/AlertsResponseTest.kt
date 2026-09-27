package ua.vidbiy.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AlertsResponseTest {

    @Test
    fun `розбирає регіони й рівні з v1`() {
        val body = """
            {"v":1,"alerts":[{"region":"49","levels":[
              {"level":"red","since":"2026-09-26T17:28:42.068Z","reason":"Ракетна загроза (червоний рівень)"},
              {"level":"yellow","since":"2026-09-26T16:38:07.711Z","reason":null}]}],
             "confirmed_at":"2026-09-27T12:27:07.488Z","age_seconds":2}
        """.trimIndent()

        val snapshot = parseAlertsResponse(body, receivedAtElapsed = 42)

        assertEquals(2L, snapshot.ageSeconds)
        assertEquals(42L, snapshot.receivedAtElapsed)
        assertEquals(
            listOf(
                ActiveLevel(
                    AlertLevel.RED,
                    Instant.parse("2026-09-26T17:28:42.068Z").toEpochMilli(),
                    "Ракетна загроза (червоний рівень)",
                ),
                ActiveLevel(AlertLevel.YELLOW, Instant.parse("2026-09-26T16:38:07.711Z").toEpochMilli(), null),
            ),
            snapshot.alerts!!["49"],
        )
    }

    @Test
    fun `порожній список — перевірено, тривог немає`() {
        val snapshot = parseAlertsResponse("""{"v":1,"alerts":[],"age_seconds":3}""", 0)

        assertEquals(emptyMap<String, List<ActiveLevel>>(), snapshot.alerts)
        assertTrue(snapshot.isKnown)
    }

    @Test
    fun `null — проксі не знає стану`() {
        val snapshot = parseAlertsResponse("""{"v":1,"alerts":null,"confirmed_at":null,"age_seconds":null}""", 0)

        assertNull(snapshot.alerts)
        assertEquals(false, snapshot.isKnown)
    }

    @Test
    fun `незнайомий рівень і регіон без рівнів — червоні`() {
        val body = """{"v":1,"alerts":[
            {"region":"8","levels":[{"level":"purple","since":"2026-09-27T10:00:00Z"}]},
            {"region":"16","levels":[]}],"age_seconds":1}"""

        val alerts = parseAlertsResponse(body, 0).alerts!!

        assertEquals(AlertLevel.RED, alerts["8"]!!.single().level)
        assertEquals(AlertLevel.RED, alerts["16"]!!.single().level)
    }
}
