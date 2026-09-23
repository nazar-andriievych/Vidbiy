package ua.vidbiy.app.data

import kotlinx.serialization.Serializable

/**
 * Один будильник. Дні тижня — числа 1..7 (понеділок..неділя), як у java.time.DayOfWeek.value.
 * Порожній набір днів означає одноразовий будильник: спрацює найближчого разу й вимкнеться.
 */
@Serializable
data class Alarm(
    val id: Long = NEW_ID,
    val hour: Int,
    val minute: Int,
    val days: Set<Int> = emptySet(),
    val enabled: Boolean = true,
    /** FR-2: враховувати повітряну тривогу. Вимкнено — це звичайний будильник. */
    val respectAlerts: Boolean = true,
    /** FR-7: крайній час очікування відбою, у хвилинах від часу будильника. */
    val maxWaitMinutes: Int = DEFAULT_MAX_WAIT_MINUTES,
    val vibrate: Boolean = true,
    /** URI системного рингтона; null — типовий сигнал будильника. */
    val ringtoneUri: String? = null,
) {
    companion object {
        const val NEW_ID = 0L
        const val DEFAULT_MAX_WAIT_MINUTES = 120
    }
}
