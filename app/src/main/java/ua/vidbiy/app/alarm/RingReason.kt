package ua.vidbiy.app.alarm

import kotlinx.serialization.Serializable
import ua.vidbiy.app.data.AlertLevel

/**
 * Чому дзвонить будильник — для блоку причини на екрані дзвінка (FR-21, design-spec 3.10).
 * Передається від того, хто вирішив дзвонити, до екрана дзвінка через Intent (як JSON).
 */
@Serializable
data class RingReason(
    val kind: Kind,
    /** «Дім» або коротка назва регіону — для текстів «у регіоні «Дім»». */
    val placeName: String? = null,
    /** Рівень, що досі триває (крайній час, тривога понад добу) — для чипа. */
    val level: AlertLevel? = null,
    /** Коли стався відбій, мс. */
    val allClearAtMillis: Long? = null,
    val pauseMinutes: Int = 0,
    val deadlineMillis: Long? = null,
) {
    enum class Kind {
        /** Звичайний будильник або відкладений дзвінок: блоку причини немає. */
        PLAIN,

        /** Тривоги не було в момент будильника — дзвонить вчасно. */
        NO_ALERT,

        /** Тривога була й закінчилася (плюс пауза). */
        ALL_CLEAR,

        DEADLINE,

        /** Свіжих даних не вдалося отримати на старті (FR-9). */
        NO_CONNECTION,

        /** Дані застаріли під час очікування (FR-15). */
        STALE,

        /** Тривога триває понад добу (FR-17). */
        TOO_LONG,
    }

    companion object {
        val Plain = RingReason(Kind.PLAIN)
    }
}

/**
 * Причина дзвінка з рішення служби очікування. [sawAlert] — чи бачили тривогу,
 * тобто чи будильник справді чекав.
 */
fun ringReasonFor(
    decision: RingDecision,
    sawAlert: Boolean,
    placeName: String?,
    level: AlertLevel?,
    allClearAtMillis: Long?,
    pauseMinutes: Int,
    deadlineMillis: Long?,
    nowMillis: Long,
): RingReason = when (decision) {
    RingDecision.RING_CLEAR ->
        if (sawAlert) {
            RingReason(
                kind = RingReason.Kind.ALL_CLEAR,
                placeName = placeName,
                allClearAtMillis = allClearAtMillis ?: nowMillis,
                pauseMinutes = pauseMinutes,
            )
        } else {
            RingReason(RingReason.Kind.NO_ALERT, placeName = placeName)
        }
    RingDecision.RING_DEADLINE ->
        RingReason(RingReason.Kind.DEADLINE, placeName = placeName, level = level, deadlineMillis = deadlineMillis)
    RingDecision.RING_ALERT_TOO_LONG ->
        RingReason(RingReason.Kind.TOO_LONG, placeName = placeName, level = level)
    RingDecision.RING_NO_DATA, RingDecision.RING_STALE ->
        RingReason(if (sawAlert) RingReason.Kind.STALE else RingReason.Kind.NO_CONNECTION, placeName = placeName)
    RingDecision.KEEP_WAITING -> RingReason.Plain
}
