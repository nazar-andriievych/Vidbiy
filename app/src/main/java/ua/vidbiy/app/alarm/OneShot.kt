package ua.vidbiy.app.alarm

import ua.vidbiy.app.data.ActiveLevel
import ua.vidbiy.app.data.Alarm
import ua.vidbiy.app.data.AlertLevel
import ua.vidbiy.app.data.Place
import ua.vidbiy.app.data.SelectedRegion
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

    /**
     * Режим чекає лише червону, а зараз діє жовта. Для рішення це те саме, що [NO_ALERT],
     * але «тривоги немає» тут вводить в оману: людина бачить тривогу в іншому застосунку.
     */
    ONLY_YELLOW,

    /** «Немає даних» — режим не вмикається. */
    NO_DATA,

    /** Ця версія застосунку застаріла й тривогам не довіряє — режим не вмикається. */
    OUTDATED,
}

/**
 * Рішення перевірки з того самого [decideRing]. Тривога понад добу не рахується (FR-27),
 * тож для режиму це «тривоги немає».
 *
 * [yellowActive] — у регіоні зараз діє свіжа жовта тривога (див. [hasFreshYellow]).
 */
fun oneShotCheck(decision: RingDecision, yellowActive: Boolean = false): OneShotCheck = when (decision) {
    RingDecision.KEEP_WAITING -> OneShotCheck.ALERT
    RingDecision.RING_CLEAR -> if (yellowActive) OneShotCheck.ONLY_YELLOW else OneShotCheck.NO_ALERT
    RingDecision.RING_ALERT_TOO_LONG -> OneShotCheck.NO_ALERT
    RingDecision.RING_OUTDATED -> OneShotCheck.OUTDATED
    else -> OneShotCheck.NO_DATA
}

/** Чи діє над регіоном жовта тривога, що почалася менше доби тому (FR-27). */
fun SelectedRegion.hasFreshYellow(alerts: Map<String, List<ActiveLevel>>?, nowMillis: Long): Boolean =
    alerts != null && levelsOver(alerts).any {
        it.level == AlertLevel.YELLOW && nowMillis - it.sinceMillis < MAX_ALERT_AGE_MILLIS
    }
