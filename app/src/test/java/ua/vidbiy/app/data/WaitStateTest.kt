package ua.vidbiy.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaitStateTest {

    private val alarm = PendingWait(alarmId = 3, deadlineMillis = 1000, startedAtMillis = 500)
    private val oneShot = PendingWait(alarmId = -1, startedAtMillis = 700)

    @Test
    fun `two waits live side by side`() {
        val waits = WaitState.withWait(WaitState.withWait(emptyList(), alarm), oneShot)
        assertEquals(listOf(alarm, oneShot), waits)
    }

    @Test
    fun `starting a wait again replaces the old one of the same alarm`() {
        val restarted = alarm.copy(startedAtMillis = 900)
        val waits = WaitState.withWait(listOf(alarm, oneShot), restarted)
        assertEquals(listOf(oneShot, restarted), waits)
    }

    @Test
    fun `finishing one wait leaves the other`() {
        val waits = WaitState.withoutWait(listOf(alarm, oneShot), alarm.alarmId)
        assertEquals(listOf(oneShot), waits)
    }

    @Test
    fun `finishing one wait drops only its status`() {
        val statuses = listOf(WaitStatus(alarmId = 3), WaitStatus(alarmId = -1))
        assertEquals(listOf(WaitStatus(alarmId = -1)), WaitState.withoutStatus(statuses, 3))
    }

    @Test
    fun `status replaces the previous status of the same wait`() {
        val old = WaitStatus(alarmId = 3, level = AlertLevel.YELLOW)
        val new = WaitStatus(alarmId = 3, level = AlertLevel.RED)
        val statuses = WaitState.withStatus(listOf(alarm), listOf(old), new)
        assertEquals(listOf(new), statuses)
    }

    @Test
    fun `status of a wait that is gone is ignored`() {
        // Служба, що саме зупинялася, не має воскрешати очікування, яке вже стерли.
        val statuses = WaitState.withStatus(emptyList(), emptyList(), WaitStatus(alarmId = 3))
        assertTrue(statuses.isEmpty())
    }

    @Test
    fun `waits survive a round trip through json`() {
        val waits = listOf(alarm, oneShot)
        assertEquals(waits, WaitState.decodeWaits(WaitState.encodeWaits(waits), legacyRaw = null))
    }

    @Test
    fun `statuses survive a round trip through json`() {
        val statuses = listOf(WaitStatus(alarmId = 3, level = AlertLevel.RED, reason = "Дрони"), WaitStatus(alarmId = -1))
        assertEquals(statuses, WaitState.decodeStatuses(WaitState.encodeStatuses(statuses), legacyRaw = null))
    }

    @Test
    fun `single wait from an older version is read as a list of one`() {
        val legacy = alarm.toJson()
        assertEquals(listOf(alarm), WaitState.decodeWaits(raw = null, legacyRaw = legacy))
    }

    @Test
    fun `new format wins over the legacy one`() {
        val waits = WaitState.decodeWaits(WaitState.encodeWaits(listOf(oneShot)), legacyRaw = alarm.toJson())
        assertEquals(listOf(oneShot), waits)
    }

    @Test
    fun `single status from an older version is read as a list of one`() {
        val legacy = """{"alarmId":3,"level":"RED"}"""
        assertEquals(listOf(WaitStatus(alarmId = 3, level = AlertLevel.RED)), WaitState.decodeStatuses(null, legacy))
    }

    @Test
    fun `nothing stored means no waits`() {
        assertTrue(WaitState.decodeWaits(null, null).isEmpty())
        assertTrue(WaitState.decodeStatuses(null, null).isEmpty())
    }

    @Test
    fun `corrupted record means no waits instead of a crash`() {
        assertTrue(WaitState.decodeWaits("not json", null).isEmpty())
        assertTrue(WaitState.decodeStatuses("{", null).isEmpty())
    }

    @Test
    fun `snoozing again replaces the previous snooze of the same alarm`() {
        val snoozes = WaitState.withSnooze(listOf(PendingSnooze(1, 1_000), PendingSnooze(-1, 2_000)), PendingSnooze(1, 5_000))
        assertEquals(listOf(PendingSnooze(-1, 2_000), PendingSnooze(1, 5_000)), snoozes)
    }

    @Test
    fun `cancelling one snooze leaves the other`() {
        val snoozes = WaitState.withoutSnooze(listOf(PendingSnooze(1, 1_000), PendingSnooze(-1, 2_000)), 1)
        assertEquals(listOf(PendingSnooze(-1, 2_000)), snoozes)
    }

    @Test
    fun `snoozes survive a round trip through json`() {
        val snoozes = listOf(PendingSnooze(1, 1_000), PendingSnooze(-1, 2_000))
        assertEquals(snoozes, WaitState.decodeSnoozes(WaitState.encodeSnoozes(snoozes)))
    }

    @Test
    fun `missing or corrupted snoozes mean none instead of a crash`() {
        assertTrue(WaitState.decodeSnoozes(null).isEmpty())
        assertTrue(WaitState.decodeSnoozes("[{").isEmpty())
    }
}
