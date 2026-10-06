package ua.vidbiy.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaitStateTest {

    private val alarm = PendingWait(alarmId = 3, deadlineMillis = 1000, startedAtMillis = 500)
    private val oneShot = PendingWait(alarmId = -1, startedAtMillis = 700)

    @Test
    fun `два очікування живуть поруч`() {
        val waits = WaitState.withWait(WaitState.withWait(emptyList(), alarm), oneShot)
        assertEquals(listOf(alarm, oneShot), waits)
    }

    @Test
    fun `нове очікування того самого будильника замінює старе`() {
        val restarted = alarm.copy(startedAtMillis = 900)
        val waits = WaitState.withWait(listOf(alarm, oneShot), restarted)
        assertEquals(listOf(oneShot, restarted), waits)
    }

    @Test
    fun `кінець одного очікування не чіпає іншого`() {
        val waits = WaitState.withoutWait(listOf(alarm, oneShot), alarm.alarmId)
        assertEquals(listOf(oneShot), waits)
    }

    @Test
    fun `кінець очікування прибирає лише його стан`() {
        val statuses = listOf(WaitStatus(alarmId = 3), WaitStatus(alarmId = -1))
        assertEquals(listOf(WaitStatus(alarmId = -1)), WaitState.withoutStatus(statuses, 3))
    }

    @Test
    fun `новий стан замінює попередній стан того самого очікування`() {
        val old = WaitStatus(alarmId = 3, level = AlertLevel.YELLOW)
        val new = WaitStatus(alarmId = 3, level = AlertLevel.RED)
        val statuses = WaitState.withStatus(listOf(alarm), listOf(old), new)
        assertEquals(listOf(new), statuses)
    }

    @Test
    fun `стан очікування, якого вже немає, ігнорується`() {
        // Служба, що саме зупинялася, не має воскрешати очікування, яке вже стерли.
        val statuses = WaitState.withStatus(emptyList(), emptyList(), WaitStatus(alarmId = 3))
        assertTrue(statuses.isEmpty())
    }

    @Test
    fun `очікування переживають запис у JSON`() {
        val waits = listOf(alarm, oneShot)
        assertEquals(waits, WaitState.decodeWaits(WaitState.encodeWaits(waits), legacyRaw = null))
    }

    @Test
    fun `стани переживають запис у JSON`() {
        val statuses = listOf(WaitStatus(alarmId = 3, level = AlertLevel.RED, reason = "Дрони"), WaitStatus(alarmId = -1))
        assertEquals(statuses, WaitState.decodeStatuses(WaitState.encodeStatuses(statuses), legacyRaw = null))
    }

    @Test
    fun `одне очікування зі старої версії читається як список з одного`() {
        val legacy = alarm.toJson()
        assertEquals(listOf(alarm), WaitState.decodeWaits(raw = null, legacyRaw = legacy))
    }

    @Test
    fun `новий формат важливіший за старий`() {
        val waits = WaitState.decodeWaits(WaitState.encodeWaits(listOf(oneShot)), legacyRaw = alarm.toJson())
        assertEquals(listOf(oneShot), waits)
    }

    @Test
    fun `один стан зі старої версії читається як список з одного`() {
        val legacy = """{"alarmId":3,"level":"RED"}"""
        assertEquals(listOf(WaitStatus(alarmId = 3, level = AlertLevel.RED)), WaitState.decodeStatuses(null, legacy))
    }

    @Test
    fun `нічого не збережено — очікувань немає`() {
        assertTrue(WaitState.decodeWaits(null, null).isEmpty())
        assertTrue(WaitState.decodeStatuses(null, null).isEmpty())
    }

    @Test
    fun `зіпсований запис — очікувань немає, а не збій`() {
        assertTrue(WaitState.decodeWaits("not json", null).isEmpty())
        assertTrue(WaitState.decodeStatuses("{", null).isEmpty())
    }

    @Test
    fun `нове відкладення замінює попереднє того самого будильника`() {
        val snoozes = WaitState.withSnooze(listOf(PendingSnooze(1, 1_000), PendingSnooze(-1, 2_000)), PendingSnooze(1, 5_000))
        assertEquals(listOf(PendingSnooze(-1, 2_000), PendingSnooze(1, 5_000)), snoozes)
    }

    @Test
    fun `скасування одного відкладення не чіпає іншого`() {
        val snoozes = WaitState.withoutSnooze(listOf(PendingSnooze(1, 1_000), PendingSnooze(-1, 2_000)), 1)
        assertEquals(listOf(PendingSnooze(-1, 2_000)), snoozes)
    }

    @Test
    fun `відкладення переживають запис у JSON`() {
        val snoozes = listOf(PendingSnooze(1, 1_000), PendingSnooze(-1, 2_000))
        assertEquals(snoozes, WaitState.decodeSnoozes(WaitState.encodeSnoozes(snoozes)))
    }

    @Test
    fun `відкладення до появи автовідкладення читаються як ручні`() {
        val snoozes = WaitState.decodeSnoozes("""[{"alarmId":1,"ringAtMillis":1000}]""")
        assertEquals(listOf(PendingSnooze(1, 1_000, autoRepeats = 0)), snoozes)
    }

    @Test
    fun `лічильник автовідкладень переживає запис у JSON`() {
        val snoozes = listOf(PendingSnooze(1, 1_000, autoRepeats = 2))
        assertEquals(snoozes, WaitState.decodeSnoozes(WaitState.encodeSnoozes(snoozes)))
    }

    @Test
    fun `відсутні чи зіпсовані відкладення — їх немає, а не збій`() {
        assertTrue(WaitState.decodeSnoozes(null).isEmpty())
        assertTrue(WaitState.decodeSnoozes("[{").isEmpty())
    }
}
