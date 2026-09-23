package ua.vidbiy.app.alarm

import ua.vidbiy.app.data.AlertsSnapshot
import ua.vidbiy.app.data.SelectedRegion

/** Чому будильник дзвонить (або чому й далі мовчить). */
enum class RingDecision {
    /** Тривоги в регіоні немає — відбій або її й не було. */
    RING_CLEAR,

    /** Настав крайній час (FR-7). */
    RING_DEADLINE,

    /** Даних про тривогу немає взагалі: мережа, проксі або alerts.in.ua мовчать. */
    RING_NO_DATA,

    /** Дані є, але старші за поріг — вірити їм не можна. */
    RING_STALE,

    /** Тривога триває, чекаємо відбою. */
    KEEP_WAITING,
    ;

    val shouldRing: Boolean get() = this != KEEP_WAITING
}

/** NFR-1: дані, старші за 3 хвилини, вважаємо непридатними. */
const val MAX_DATA_AGE_SECONDS = 180L

/**
 * Єдине місце, де вирішується «дзвонити чи чекати».
 *
 * Функція навмисно чиста: жодних годинників, мережі й Android усередині — усе приходить
 * аргументами. Саме тут живе fail-safe (NFR-1), тож ця логіка має бути перевірюваною
 * тестами до останньої гілки.
 */
fun decideRing(
    snapshot: AlertsSnapshot?,
    nowElapsed: Long,
    region: SelectedRegion?,
    pastDeadline: Boolean,
): RingDecision {
    if (pastDeadline) return RingDecision.RING_DEADLINE
    // Регіон не обрано — чекати нема на що.
    if (region == null) return RingDecision.RING_NO_DATA

    val alertUids = snapshot?.alertUids ?: return RingDecision.RING_NO_DATA
    val age = snapshot.effectiveAgeSeconds(nowElapsed) ?: return RingDecision.RING_NO_DATA
    if (age > MAX_DATA_AGE_SECONDS) return RingDecision.RING_STALE

    return if (region.isUnderAlert(alertUids)) RingDecision.KEEP_WAITING else RingDecision.RING_CLEAR
}
