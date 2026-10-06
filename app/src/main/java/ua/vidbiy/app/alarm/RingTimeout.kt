package ua.vidbiy.app.alarm

/**
 * Що робити, коли дзвінок відіграв своє, а його ніхто не вимкнув і не відклав (FR-21a).
 *
 * Людина, що не почула дзвінок, — саме та, кого треба розбудити, тож замовкнути назавжди не можна.
 * Будильник відкладає себе сам, як після натиску «Відкласти», і лише після [MAX_AUTO_SNOOZES]
 * таких повторів здається: далі, найімовірніше, людини просто немає поруч з телефоном.
 */
sealed interface RingTimeout {
    /** Відкласти на звичайну тривалість відкладення; [autoRepeats] — номер цього автовідкладення. */
    data class AutoSnooze(val autoRepeats: Int) : RingTimeout

    /** Замовкнути й лишити сповіщення «Пропущений будильник». */
    data object GiveUp : RingTimeout
}

/** Два автовідкладення — разом три дзвінки, ~50 хв з типовим відкладенням на 10 хв. */
const val MAX_AUTO_SNOOZES = 2

/** [autoRepeatsSoFar] — скільки разів цей дзвінок уже відкладався сам (0 — перший дзвінок). */
fun ringTimeout(autoRepeatsSoFar: Int): RingTimeout =
    if (autoRepeatsSoFar < MAX_AUTO_SNOOZES) RingTimeout.AutoSnooze(autoRepeatsSoFar + 1) else RingTimeout.GiveUp
