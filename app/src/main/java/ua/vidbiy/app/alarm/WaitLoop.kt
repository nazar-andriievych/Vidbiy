package ua.vidbiy.app.alarm

import ua.vidbiy.app.data.Alarm
import ua.vidbiy.app.data.AlertsSnapshot
import ua.vidbiy.app.data.WaitStatus

/** NFR-3 дозволяє до 2 хв затримки після відбою, тож опитування раз на 30 с дає запас. */
const val POLL_INTERVAL_MILLIS = 30_000L

/** FR-8: скільки на старті пробуємо отримати свіжі дані, перш ніж дзвонити без них. */
const val STARTUP_WINDOW_MILLIS = 30_000L
const val STARTUP_RETRY_MILLIS = 2_000L

/**
 * Що служба очікування пам'ятає між опитуваннями.
 *
 * [known] — остання відповідь, яка хоч щось знала: невдала спроба її не затирає (FR-15).
 * [sawAlert] — чи бачили тривогу: без неї відбій до часу будильника нічого не означає (FR-10).
 * [allClearAtElapsed] / [allClearAtMillis] — коли почалася пауза після відбою (FR-14);
 * монотонний лічильник — для відліку, годинник — для показу «Відбій о 06:12».
 */
data class WaitLoopState(
    val known: AlertsSnapshot? = null,
    val sawAlert: Boolean = false,
    val allClearAtElapsed: Long? = null,
    val allClearAtMillis: Long? = null,
    /**
     * Очікування відновлене (вартовий, оновлення, перезавантаження), а не почате щойно.
     * Тоді на старті теж даємо мережі [STARTUP_WINDOW_MILLIS]: телефон, який щойно розбудили
     * з глибокого сну, перші секунди часто без мережі — це не привід дзвонити.
     */
    val resumed: Boolean = false,
)

/**
 * Стан для відновленого очікування з того, що служба встигла записати ([WaitStatus]).
 * Пауза після відбою відновлюється за годинником: монотонний лічильник після
 * перезавантаження починається з нуля.
 */
fun resumedWaitState(status: WaitStatus?, nowElapsed: Long, nowMillis: Long): WaitLoopState {
    val clearAt = status?.allClearAtMillis
    return WaitLoopState(
        sawAlert = status != null && (status.sawAlert || status.level != null || clearAt != null),
        allClearAtElapsed = clearAt?.let { nowElapsed - (nowMillis - it).coerceAtLeast(0) },
        allClearAtMillis = clearAt,
        resumed = true,
    )
}

/** Що робить вартовий, коли служба очікування давно не відсувала його. */
enum class WatchdogAction {
    /** Службу, ймовірно, приспала система (Samsung «глибокий сон»): розбудити й чекати далі. */
    REVIVE,

    /** Службу вже будили, а вона так і не почала опитування: зламалася — дзвонити (NFR-1). */
    RING,
}

/**
 * Вартовий: служба мовчить. Уперше — будимо її (у глибокому сні Samsung заморожує застосунок,
 * і лише будильникові таймери його розморожують). Якщо після попереднього пробудження служба
 * так і не почала опитування — дзвонимо.
 */
fun watchdogAction(status: WaitStatus?): WatchdogAction {
    val revivedAt = status?.revivedAtMillis ?: return WatchdogAction.REVIVE
    val polledAt = status.polledAtMillis ?: return WatchdogAction.RING
    return if (polledAt > revivedAt) WatchdogAction.REVIVE else WatchdogAction.RING
}

/** Що робити після одного опитування. */
sealed interface WaitAction {
    /** FR-8: на старті даних ще немає — спробувати знову за [STARTUP_RETRY_MILLIS]. */
    data object Retry : WaitAction

    /** Чекати далі; наступне опитування — за [delayMillis]. */
    data class Wait(val delayMillis: Long) : WaitAction

    /** Дзвонити: [decision] — причина для екрана дзвінка. */
    data class Ring(val decision: RingDecision) : WaitAction
}

data class WaitTick(
    val state: WaitLoopState,
    /** Дані, на яких ухвалено рішення (нова відповідь або попередня відома). */
    val snapshot: AlertsSnapshot,
    val decision: RingDecision,
    val action: WaitAction,
) {
    /** Для журналу рішень: `retry` / `ring` / `pause` / `wait`. */
    val logStep: String
        get() = when (action) {
            WaitAction.Retry -> "retry"
            is WaitAction.Ring -> "ring"
            is WaitAction.Wait -> if (state.allClearAtElapsed != null) "pause" else "wait"
        }
}

/**
 * Один крок циклу очікування: усе рішення без мережі, годинників і Android — їх подає
 * [AlarmWaitService]. Тут зводяться разом FR-8 (стартове вікно), FR-10 … FR-17 і доба
 * очікування як остання страховка.
 *
 * [alarm] — налаштування, з якими почалося очікування (FR-7c). [giveUpAtMillis] — крайній
 * час або доба очікування ([ua.vidbiy.app.data.PendingWait.giveUpAtMillis]).
 */
fun waitTick(
    state: WaitLoopState,
    fetched: AlertsSnapshot,
    alarm: Alarm,
    deadlineMillis: Long?,
    giveUpAtMillis: Long,
    startedElapsed: Long,
    nowElapsed: Long,
    nowMillis: Long,
): WaitTick {
    val snapshot = fetched.orPrevious(state.known)
    var decision = decideRing(
        snapshot = snapshot,
        nowElapsed = nowElapsed,
        nowMillis = nowMillis,
        region = alarm.region,
        waitFor = alarm.waitFor,
        pastDeadline = deadlineMillis?.let { nowMillis >= it } ?: false,
    )
    // Доба очікування без крайнього часу: тривога могла оновлюватися, але чекати далі не можна.
    if (!decision.shouldRing && nowMillis >= giveUpAtMillis) decision = RingDecision.RING_ALERT_TOO_LONG
    val withSnapshot = state.copy(known = snapshot)

    // FR-8: на старті до 30 с даємо мережі шанс, перш ніж дзвонити через брак даних.
    val noFreshData = decision == RingDecision.RING_NO_DATA || decision == RingDecision.RING_STALE
    if (noFreshData && (!state.sawAlert || state.resumed) && nowElapsed - startedElapsed < STARTUP_WINDOW_MILLIS) {
        return WaitTick(withSnapshot, snapshot, decision, WaitAction.Retry)
    }

    val step = nextWaitStep(decision, state.sawAlert, state.allClearAtElapsed, alarm.pauseMinutes, nowElapsed)
    if (step is WaitStep.Ring) return WaitTick(withSnapshot, snapshot, decision, WaitAction.Ring(step.reason))
    step as WaitStep.Wait

    val allClearAtMillis = when {
        step.allClearAtElapsed == null -> null
        state.allClearAtElapsed == null -> nowMillis
        else -> state.allClearAtMillis
    }
    val next = withSnapshot.copy(
        sawAlert = state.sawAlert || decision == RingDecision.KEEP_WAITING,
        allClearAtElapsed = step.allClearAtElapsed,
        allClearAtMillis = allClearAtMillis,
    )
    // Під час паузи будимося рівно до її кінця, якщо він ближчий за звичайне опитування.
    val untilPauseEnd = step.allClearAtElapsed?.let { it + alarm.pauseMinutes * 60_000L - nowElapsed }
    val delay = untilPauseEnd?.coerceIn(1_000L, POLL_INTERVAL_MILLIS) ?: POLL_INTERVAL_MILLIS
    return WaitTick(next, snapshot, decision, WaitAction.Wait(delay))
}
