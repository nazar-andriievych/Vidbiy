package ua.vidbiy.app.alarm

import kotlinx.serialization.Serializable
import ua.vidbiy.app.data.Alarm
import ua.vidbiy.app.data.PendingSnooze
import ua.vidbiy.app.data.PendingWait
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Що треба знати, щоб задзвонити після перезавантаження, поки телефон ще не розблокували.
 *
 * До першого розблокування сховище будильників недоступне (воно зашифроване ключем, що
 * залежить від PIN). Тому поруч, у сховищі, доступному одразу після ввімкнення, лежить ця
 * копія — лише розклад: час, дні, дата. Ні регіону, ні місць, ні мелодії: будильник до
 * розблокування тривогу не перевіряє, а просто дзвонить (NFR-1).
 */
@Serializable
data class LockedBootPlan(
    /** Увімкнені будильники, лише розклад (див. [lockedCopy]). */
    val alarms: List<Alarm> = emptyList(),
    /** Відкладені дзвінки. */
    val snoozes: List<PendingSnooze> = emptyList(),
    /** Будильники, що чекали відбою: після перезавантаження вони дзвонять одразу. */
    val waiting: List<Alarm> = emptyList(),
    /** Розклад будильників, що відкладені, — лише для підпису «Будильник 06:45» на екрані дзвінка. */
    val snoozed: List<Alarm> = emptyList(),
    /** FR-19: тривалість відкладення — щоб кнопка на екрані дзвінка знала її без сховища. */
    val snoozeMinutes: Int = 5,
)

/** Що вже задзвонило до розблокування — після розблокування це переноситься в справжнє сховище. */
@Serializable
data class LockedFired(val alarmId: Long, val kind: LockedFire.Kind, val atMillis: Long)

/** Одне спрацювання, поставлене до розблокування. */
@Serializable
data class LockedFire(
    val alarmId: Long,
    val atMillis: Long,
    val kind: Kind,
    val hour: Int,
    val minute: Int,
    val vibrate: Boolean = true,
    /** Будильник з вимкненим «враховувати тривоги» дзвонить без блоку причини (FR-21). */
    val respectAlerts: Boolean = true,
    /** FR-21a: скільки разів цей дзвінок уже відкладався сам. */
    val autoRepeats: Int = 0,
) {
    enum class Kind {
        /** Звичайний час будильника. */
        ALARM,

        /** Відкладений дзвінок. */
        SNOOZE,

        /** Будильник чекав відбою, коли телефон перезавантажився. */
        WAIT,
    }

    /** Звичайний час і «позачергові» дзвінки мають окремі спрацювання в AlarmManager. */
    val slot: Slot get() = if (kind == Kind.ALARM) Slot.ALARM else Slot.EXTRA

    enum class Slot { ALARM, EXTRA }
}

/** Лише розклад: регіон, місце й мелодія до сховища, доступного без PIN, не потрапляють. */
fun Alarm.lockedCopy(): Alarm = Alarm(
    id = id,
    hour = hour,
    minute = minute,
    days = days,
    date = date,
    respectAlerts = respectAlerts,
    vibrate = vibrate,
)

fun lockedBootPlan(
    alarms: List<Alarm>,
    snoozes: List<PendingSnooze>,
    waits: List<PendingWait>,
    snoozeMinutes: Int,
): LockedBootPlan = LockedBootPlan(
    alarms = alarms.filter { it.enabled }.map { it.lockedCopy() },
    snoozes = snoozes,
    snoozed = alarms.filter { alarm -> snoozes.any { it.alarmId == alarm.id } }.map { it.lockedCopy() },
    // Разового режиму немає серед будильників, тож беремо копію з самого очікування.
    waiting = waits.mapNotNull { wait -> (wait.alarm ?: alarms.firstOrNull { it.id == wait.alarmId })?.lockedCopy() },
    snoozeMinutes = snoozeMinutes,
)

/** Відкладення, проґавлене більш ніж на годину, уже не дзвонить — як і в [Snoozes.restore]. */
private const val LOCKED_SNOOZE_STALE_MILLIS = 60 * 60_000L

/**
 * Які спрацювання поставити до розблокування: по одному на кожне місце ([LockedFire.Slot])
 * кожного будильника — найближче з тих, що ще не задзвонили.
 *
 * [lockedSnoozes] — відкладення, зроблені вже до розблокування (з екрана дзвінка).
 */
fun lockedFires(
    plan: LockedBootPlan,
    lockedSnoozes: List<PendingSnooze>,
    fired: List<LockedFired>,
    now: LocalDateTime,
    nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<LockedFire> {
    fun firedAlready(alarmId: Long, kind: LockedFire.Kind, atMillis: Long? = null) =
        fired.any { it.alarmId == alarmId && it.kind == kind && (atMillis == null || it.atMillis == atMillis) }

    val fires = mutableListOf<LockedFire>()
    val byId = (plan.snoozed + plan.waiting + plan.alarms).associateBy { it.id }

    for (alarm in plan.waiting) {
        if (firedAlready(alarm.id, LockedFire.Kind.WAIT)) continue
        // Чекав відбою, а даних про тривогу тепер немає: дзвонимо одразу (NFR-1).
        fires += alarm.fire(nowMillis, LockedFire.Kind.WAIT)
    }

    // Відкладення того самого будильника, зроблене пізніше, замінює попереднє.
    val snoozes = (plan.snoozes.filter { s -> lockedSnoozes.none { it.alarmId == s.alarmId } } + lockedSnoozes)
    for (snooze in snoozes) {
        if (firedAlready(snooze.alarmId, LockedFire.Kind.SNOOZE, snooze.ringAtMillis)) continue
        if (nowMillis - snooze.ringAtMillis > LOCKED_SNOOZE_STALE_MILLIS) continue
        val alarm = byId[snooze.alarmId] ?: Alarm(id = snooze.alarmId, hour = 0, minute = 0)
        fires += alarm.fire(snooze.ringAtMillis, LockedFire.Kind.SNOOZE, snooze.autoRepeats)
    }

    for (alarm in plan.alarms) {
        // Одноразовий, що вже задзвонив, вимкнувся б — до розблокування вимкнути його нема де.
        if (alarm.days.isEmpty() && firedAlready(alarm.id, LockedFire.Kind.ALARM)) continue
        val next = alarm.nextTriggerAt(now) ?: continue
        fires += alarm.fire(next.toEpochMillis(zone), LockedFire.Kind.ALARM)
    }

    return fires
        .groupBy { it.alarmId to it.slot }
        .map { (_, sameSlot) -> sameSlot.minBy { it.atMillis } }
}

private fun Alarm.fire(atMillis: Long, kind: LockedFire.Kind, autoRepeats: Int = 0) = LockedFire(
    alarmId = id,
    atMillis = atMillis,
    kind = kind,
    hour = hour,
    minute = minute,
    vibrate = vibrate,
    respectAlerts = respectAlerts,
    autoRepeats = autoRepeats,
)
