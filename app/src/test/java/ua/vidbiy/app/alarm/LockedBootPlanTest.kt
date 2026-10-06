package ua.vidbiy.app.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json
import ua.vidbiy.app.data.Alarm
import ua.vidbiy.app.data.PendingSnooze
import ua.vidbiy.app.data.PendingWait
import ua.vidbiy.app.data.SelectedRegion
import ua.vidbiy.app.data.WaitFor
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class LockedBootPlanTest {

    private val kyiv = ZoneId.of("Europe/Kyiv")

    // 2026-10-07, середа, 03:00 — телефон перезавантажився вночі.
    private val night = LocalDateTime.of(2026, 10, 7, 3, 0)
    private val nightMillis = night.toEpochMillis(kyiv)
    private val minute = 60_000L

    private val bucha = SelectedRegion("702", "Буча", coveringUids = setOf("702", "75", "14"))
    private val daily = Alarm(id = 1, hour = 7, minute = 0, days = setOf(1, 2, 3, 4, 5, 6, 7), region = bucha, placeId = 3)
    private val once = Alarm(id = 2, hour = 6, minute = 30, region = bucha)

    private fun fires(
        plan: LockedBootPlan,
        lockedSnoozes: List<PendingSnooze> = emptyList(),
        fired: List<LockedFired> = emptyList(),
        now: LocalDateTime = night,
        nowMillis: Long = nightMillis,
    ) = lockedFires(plan, lockedSnoozes, fired, now, nowMillis, kyiv)

    @Test
    fun `у копію йде лише розклад — без регіону, місця й мелодії`() {
        val withEverything = daily.copy(ringtoneUri = "content://ringtone", waitFor = WaitFor.RED_ONLY, pauseMinutes = 5, deadlineMinute = 540)
        val copy = lockedBootPlan(listOf(withEverything), emptyList(), emptyList(), snoozeMinutes = 7).alarms.single()

        assertNull(copy.region)
        assertNull(copy.placeId)
        assertNull(copy.ringtoneUri)
        assertNull(copy.deadlineMinute)
        assertEquals(daily.days, copy.days)
        assertEquals(7 to 0, copy.hour to copy.minute)
        val raw = Json.encodeToString(LockedBootPlan.serializer(), lockedBootPlan(listOf(withEverything), emptyList(), emptyList(), 7))
        assertTrue(raw, "702" !in raw && "Буча" !in raw)
    }

    @Test
    fun `вимкнені будильники в копію не йдуть`() {
        val plan = lockedBootPlan(listOf(daily, once.copy(enabled = false)), emptyList(), emptyList(), 5)
        assertEquals(listOf(1L), plan.alarms.map { it.id })
    }

    @Test
    fun `після перезавантаження ставляться найближчі часи будильників`() {
        val plan = lockedBootPlan(listOf(daily, once), emptyList(), emptyList(), 5)

        val byId = fires(plan).associateBy { it.alarmId }

        assertEquals(LocalDateTime.of(2026, 10, 7, 7, 0).toEpochMillis(kyiv), byId[1]!!.atMillis)
        assertEquals(LocalDateTime.of(2026, 10, 7, 6, 30).toEpochMillis(kyiv), byId[2]!!.atMillis)
        assertTrue(byId.values.all { it.kind == LockedFire.Kind.ALARM })
    }

    @Test
    fun `одноразовий, що вже задзвонив до розблокування, більше не ставиться`() {
        val plan = lockedBootPlan(listOf(once), emptyList(), emptyList(), 5)
        val firedAt = LocalDateTime.of(2026, 10, 7, 6, 30)
        val fired = listOf(LockedFired(2, LockedFire.Kind.ALARM, firedAt.toEpochMillis(kyiv)))

        assertTrue(fires(plan, fired = fired, now = firedAt.plusSeconds(5), nowMillis = firedAt.toEpochMillis(kyiv) + 5_000).isEmpty())
    }

    @Test
    fun `щоденний після дзвінка до розблокування ставиться на завтра`() {
        val plan = lockedBootPlan(listOf(daily), emptyList(), emptyList(), 5)
        val firedAt = LocalDateTime.of(2026, 10, 7, 7, 0)
        val fired = listOf(LockedFired(1, LockedFire.Kind.ALARM, firedAt.toEpochMillis(kyiv)))

        val next = fires(plan, fired = fired, now = firedAt.plusSeconds(5), nowMillis = firedAt.toEpochMillis(kyiv) + 5_000).single()

        assertEquals(LocalDateTime.of(2026, 10, 8, 7, 0).toEpochMillis(kyiv), next.atMillis)
    }

    @Test
    fun `будильник, що чекав відбою, дзвонить одразу — даних про тривогу немає`() {
        val wait = PendingWait(alarmId = 1, startedAtMillis = nightMillis - 60 * minute, alarm = daily)
        val plan = lockedBootPlan(listOf(daily), emptyList(), listOf(wait), 5)

        val fires = fires(plan)

        val now = fires.single { it.slot == LockedFire.Slot.EXTRA }
        assertEquals(LockedFire.Kind.WAIT, now.kind)
        assertEquals(nightMillis, now.atMillis)
        // Звичайний наступний час нікуди не дівається.
        assertEquals(LockedFire.Kind.ALARM, fires.single { it.slot == LockedFire.Slot.ALARM }.kind)
    }

    @Test
    fun `разовий режим, що чекав, теж дзвонить — його копія береться з очікування`() {
        val oneShot = Alarm(id = OneShot.ONE_SHOT_ID, hour = 1, minute = 10, region = bucha)
        val plan = lockedBootPlan(emptyList(), emptyList(), listOf(PendingWait(OneShot.ONE_SHOT_ID, alarm = oneShot)), 5)

        val fire = fires(plan).single()

        assertEquals(OneShot.ONE_SHOT_ID, fire.alarmId)
        assertEquals(LockedFire.Kind.WAIT, fire.kind)
        assertNull(plan.waiting.single().region)
    }

    @Test
    fun `очікування дзвонить лише раз`() {
        val plan = lockedBootPlan(emptyList(), emptyList(), listOf(PendingWait(1, alarm = once.copy(id = 1))), 5)
        val fired = listOf(LockedFired(1, LockedFire.Kind.WAIT, nightMillis))

        assertTrue(fires(plan, fired = fired).isEmpty())
    }

    @Test
    fun `відкладення до перезавантаження дзвонить у свій час, з лічильником повторів`() {
        val snooze = PendingSnooze(alarmId = 2, ringAtMillis = nightMillis + 5 * minute, autoRepeats = 1)
        val plan = lockedBootPlan(listOf(once.copy(enabled = false)), listOf(snooze), emptyList(), 5)

        val fire = fires(plan).single()

        assertEquals(LockedFire.Kind.SNOOZE, fire.kind)
        assertEquals(snooze.ringAtMillis, fire.atMillis)
        assertEquals(1, fire.autoRepeats)
        // Підпис «Будильник 06:30» — з копії відкладеного будильника, хоч він і вимкнений.
        assertEquals(6 to 30, fire.hour to fire.minute)
    }

    @Test
    fun `відкладення, проґавлене більш ніж на годину, не дзвонить`() {
        val plan = LockedBootPlan(snoozes = listOf(PendingSnooze(2, nightMillis - 61 * minute)))
        assertTrue(fires(plan).isEmpty())
    }

    @Test
    fun `відкладення до розблокування замінює давнє відкладення того самого будильника`() {
        val plan = LockedBootPlan(snoozes = listOf(PendingSnooze(2, nightMillis + 30 * minute)))
        val again = PendingSnooze(2, nightMillis + 10 * minute)

        assertEquals(listOf(again.ringAtMillis), fires(plan, lockedSnoozes = listOf(again)).map { it.atMillis })
    }

    @Test
    fun `на одне місце — найближче з позачергових дзвінків`() {
        val plan = lockedBootPlan(
            alarms = listOf(daily),
            snoozes = listOf(PendingSnooze(1, nightMillis + 5 * minute)),
            waits = listOf(PendingWait(1, alarm = daily)),
            snoozeMinutes = 5,
        )

        val extra = fires(plan).single { it.slot == LockedFire.Slot.EXTRA }

        assertEquals(LockedFire.Kind.WAIT, extra.kind)
    }

    @Test
    fun `будильник з датою ставиться в ту дату, а минулого не ставить`() {
        val dated = Alarm(id = 4, hour = 8, minute = 0, date = LocalDate.of(2026, 10, 9))
        val past = Alarm(id = 5, hour = 8, minute = 0, date = LocalDate.of(2026, 10, 6))
        val plan = lockedBootPlan(listOf(dated, past), emptyList(), emptyList(), 5)

        assertEquals(listOf(4L), fires(plan).map { it.alarmId })
    }

    @Test
    fun `розклад і тривалість відкладення переживають запис у JSON`() {
        val plan = lockedBootPlan(listOf(daily), listOf(PendingSnooze(1, 1_000, 2)), listOf(PendingWait(1, alarm = daily)), 9)
        val json = Json { ignoreUnknownKeys = true }

        assertEquals(plan, json.decodeFromString(LockedBootPlan.serializer(), json.encodeToString(LockedBootPlan.serializer(), plan)))
    }
}
