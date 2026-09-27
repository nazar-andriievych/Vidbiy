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
    /** FR-4: на які рівні тривоги зважати. */
    val waitFor: WaitFor = WaitFor.RED_AND_YELLOW,
    /**
     * FR-6: крайній час — хвилина доби (0..1439), абсолютна. null — не заданий.
     * Якщо він не пізніший за час будильника, це наступна доба.
     */
    val deadlineMinute: Int? = null,
    val vibrate: Boolean = true,
    /** URI системного рингтона; null — типовий сигнал будильника. */
    val ringtoneUri: String? = null,
) {
    companion object {
        const val NEW_ID = 0L
    }
}

/** FR-4: чекати відбою лише червоної тривоги чи будь-якої. */
@Serializable
enum class WaitFor {
    RED_AND_YELLOW,

    /** Жовта (дронова загроза) не заважає дзвонити; зміна червоної на жовту — відбій (FR-13). */
    RED_ONLY,
}
