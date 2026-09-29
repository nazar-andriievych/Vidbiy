package ua.vidbiy.app.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.LocalDate

/**
 * Один будильник. Дні тижня — числа 1..7 (понеділок..неділя), як у java.time.DayOfWeek.value.
 * Порожній набір днів означає одноразовий будильник: спрацює найближчого разу (або в [date])
 * й вимкнеться.
 */
@Serializable
data class Alarm(
    val id: Long = NEW_ID,
    val hour: Int,
    val minute: Int,
    val days: Set<Int> = emptySet(),
    /**
     * Дата одноразового будильника; null — найближчий раз (сьогодні або завтра).
     * Лише для будильника без днів тижня: одне виключає інше, як у годиннику Samsung.
     * У момент спрацювання обнуляється разом з [enabled].
     */
    @Serializable(with = LocalDateIsoSerializer::class)
    val date: LocalDate? = null,
    val enabled: Boolean = true,
    /** FR-2: враховувати повітряну тривогу. Вимкнено — це звичайний будильник. */
    val respectAlerts: Boolean = true,
    /**
     * FR-3: регіон, у якому будильник зважає на тривоги. null — не обрано: будильник
     * дзвонить як звичайний. Це власна копія, а не посилання на місце: у момент дзвінка
     * рішення не має залежати від ще одного сховища.
     */
    val region: SelectedRegion? = null,
    /** Місце з «Моїх місць», з якого взято [region]; null — регіон обрано напряму або місце видалене. */
    val placeId: Long? = null,
    /** FR-4: на які рівні тривоги зважати. */
    val waitFor: WaitFor = WaitFor.RED_AND_YELLOW,
    /** FR-5: скільки хвилин чекати після відбою, перш ніж дзвонити. */
    val pauseMinutes: Int = 0,
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

        /** Варіанти паузи після відбою в інтерфейсі (design-spec 3.2). */
        val PAUSE_OPTIONS = listOf(0, 2, 5, 10, 15, 30)
    }
}

/** FR-4: чекати відбою лише червоної тривоги чи будь-якої. */
@Serializable
enum class WaitFor {
    RED_AND_YELLOW,

    /** Жовта (дронова загроза) не заважає дзвонити; зміна червоної на жовту — відбій (FR-13). */
    RED_ONLY,
}

/** LocalDate як «2026-10-09»: так дата читається і в резервній копії, і в журналі. */
object LocalDateIsoSerializer : KSerializer<LocalDate> {
    override val descriptor = PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}
