package ua.vidbiy.app.alarm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Один будильник, що зараз дзвонить: час, з яким він був поставлений, і чому він дзвонить. */
data class RingEntry(
    val alarmId: Long,
    val hour: Int,
    val minute: Int,
    val reason: RingReason,
    /** Скільки разів цей дзвінок уже відкладався сам (FR-21a). */
    val autoRepeats: Int = 0,
)

/**
 * Будильники, що дзвонять просто зараз. Дзвінок один на всіх (як у звичайному годиннику):
 * якщо поки він дзвонить спрацьовує ще один будильник, він долучається сюди, а не замінює перший.
 *
 * Пише служба дзвінка, читають екран дзвінка й головний екран (щоб відкрити дзвінок, що вже йде) —
 * вони в одному процесі, тож спільного об'єкта досить.
 */
object RingState {
    private val _entries = MutableStateFlow<List<RingEntry>>(emptyList())
    val entries: StateFlow<List<RingEntry>> = _entries.asStateFlow()

    /** Тривалість відкладення поточного дзвінка — для кнопки «Відкласти на X хв». */
    var snoozeMinutes: Int = 0
        private set

    fun set(entries: List<RingEntry>, snoozeMinutes: Int = this.snoozeMinutes) {
        this.snoozeMinutes = snoozeMinutes
        _entries.value = entries
    }
}
