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
)

/**
 * Будильники, що дзвонять просто зараз. Дзвінок один на всіх (як у звичайному годиннику):
 * якщо поки він дзвонить спрацьовує ще один будильник, він долучається сюди, а не замінює перший.
 *
 * Пише служба дзвінка, читає екран дзвінка — вони в одному процесі, тож спільного об'єкта досить.
 */
object RingState {
    private val _entries = MutableStateFlow<List<RingEntry>>(emptyList())
    val entries: StateFlow<List<RingEntry>> = _entries.asStateFlow()

    fun set(entries: List<RingEntry>) {
        _entries.value = entries
    }
}
