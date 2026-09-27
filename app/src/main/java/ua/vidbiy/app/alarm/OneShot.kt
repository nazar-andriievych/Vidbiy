package ua.vidbiy.app.alarm

import ua.vidbiy.app.data.Alarm
import ua.vidbiy.app.data.Place
import ua.vidbiy.app.data.WaitFor
import java.time.LocalTime

/**
 * Разовий режим «Розбуди після відбою» (FR-22 … FR-25).
 *
 * Окремого механізму для нього немає: це «віртуальний» будильник з id [ONE_SHOT_ID],
 * якого немає в списку будильників. Його збирають щоразу з налаштувань режиму й основного
 * місця, а далі працює те саме очікування, пауза й дзвінок, що й для звичайних будильників.
 * Крайнього часу в разовому режимі немає (FR-24).
 */
object OneShot {
    const val ONE_SHOT_ID = -1L

    fun alarm(primary: Place?, waitFor: WaitFor, pauseMinutes: Int, startedAt: LocalTime = LocalTime.now()) = Alarm(
        id = ONE_SHOT_ID,
        hour = startedAt.hour,
        minute = startedAt.minute,
        respectAlerts = true,
        region = primary?.region,
        placeId = primary?.id,
        waitFor = waitFor,
        pauseMinutes = pauseMinutes,
        deadlineMinute = null,
    )
}

/** Чим закінчилася перевірка після натискання кнопки режиму (FR-23). */
enum class OneShotCheck {
    /** Тривога є — починаємо очікування. */
    ALERT,

    /** «Зараз тривоги немає» — режим не потрібен. */
    NO_ALERT,

    /** «Немає даних» — режим не вмикається. */
    NO_DATA,
}

/**
 * Рішення перевірки з того самого [decideRing]. Тривога понад добу не рахується (FR-27),
 * тож для режиму це «тривоги немає».
 */
fun oneShotCheck(decision: RingDecision): OneShotCheck = when (decision) {
    RingDecision.KEEP_WAITING -> OneShotCheck.ALERT
    RingDecision.RING_CLEAR, RingDecision.RING_ALERT_TOO_LONG -> OneShotCheck.NO_ALERT
    else -> OneShotCheck.NO_DATA
}
