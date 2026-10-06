package ua.vidbiy.app.alarm

import ua.vidbiy.app.data.Alarm

/**
 * Рішення [AlarmReceiver] у момент спрацювання — без Android і сховища, щоб їх можна
 * було перевірити тестами. Сам приймач лише читає дані й виконує те, що тут вирішено.
 */
enum class FireKind {
    /** Звичайний час будильника. */
    REGULAR,

    /** Відкладений дзвінок (FR-20). */
    SNOOZE,

    /** Страховка очікування: крайній час або доба (FR-16, FR-17). */
    DEADLINE,
}

/**
 * Що робити зі спрацюванням: чекати відбою (перевірка тривоги — у службі очікування)
 * чи одразу дзвонити з такою причиною.
 */
sealed interface FireAction {
    data object WaitForAllClear : FireAction

    data class Ring(val reason: RingReason.Kind) : FireAction
}

/**
 * - Звичайний час з «враховувати тривоги» й регіоном — очікування (FR-8 … FR-11).
 * - Тривоги не враховуються або регіону немає — звичайний будильник без блоку причини (FR-2, FR-21).
 * - Відкладення дзвонить незалежно від тривоги (FR-20).
 * - Страховка очікування — крайній час або доба ([backstopReason]); якщо очікування вже немає
 *   (його закінчили раніше), лишається звичайний дзвінок: краще зайвий, ніж тиша.
 */
fun fireAction(kind: FireKind, alarm: Alarm, hasWait: Boolean, deadlineMillis: Long?, nowMillis: Long): FireAction = when {
    kind == FireKind.REGULAR && alarm.respectAlerts && alarm.region != null -> FireAction.WaitForAllClear
    kind == FireKind.DEADLINE && hasWait -> FireAction.Ring(backstopReason(deadlineMillis, nowMillis))
    else -> FireAction.Ring(RingReason.Kind.PLAIN)
}

/**
 * Страховка очікування спрацювала: це крайній час, якщо він настав, інакше — доба очікування (FR-17).
 * Хвилина запасу — AlarmManager може розбудити трохи раніше заданого моменту.
 */
fun backstopReason(deadlineMillis: Long?, nowMillis: Long): RingReason.Kind =
    if (deadlineMillis != null && nowMillis >= deadlineMillis - BACKSTOP_EARLY_MILLIS) {
        RingReason.Kind.DEADLINE
    } else {
        RingReason.Kind.TOO_LONG
    }

private const val BACKSTOP_EARLY_MILLIS = 60_000L

/**
 * Після звичайного спрацювання: одноразовий будильник вимикається разом з датою (FR-1a) —
 * інакше вимкнений пам'ятав би минулу дату, а відкритий для правки не давав би зберегти.
 * null — будильник повторюваний, його треба поставити на наступний раз.
 */
fun Alarm.afterRegularFire(): Alarm? = if (days.isEmpty()) copy(enabled = false, date = null) else null
