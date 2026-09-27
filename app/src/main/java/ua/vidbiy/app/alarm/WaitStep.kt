package ua.vidbiy.app.alarm

/** Що робити після чергової перевірки під час очікування. */
sealed interface WaitStep {
    data class Ring(val reason: RingDecision) : WaitStep

    /**
     * Чекати далі. [allClearAtElapsed] — коли стався відбій (за монотонним лічильником),
     * якщо зараз іде пауза після відбою; null — триває тривога.
     */
    data class Wait(val allClearAtElapsed: Long?) : WaitStep
}

/**
 * Пауза після відбою (FR-10, FR-13, FR-14) поверх рішення [decideRing].
 *
 * - [sawAlert] = false: тривоги ще не бачили, це момент будильника. Відбій, що стався
 *   раніше, ні на що не впливає — дзвонимо одразу (FR-10).
 * - Тривога зникла під час очікування: чекаємо [pauseMinutes]; якщо за цей час вона
 *   повернулася — пауза скидається й очікування триває (FR-14).
 * - Будь-яка інша причина дзвонити (крайній час, немає даних, тривога понад добу)
 *   діє й під час паузи: fail-safe важливіший за паузу.
 */
fun nextWaitStep(
    decision: RingDecision,
    sawAlert: Boolean,
    allClearAtElapsed: Long?,
    pauseMinutes: Int,
    nowElapsed: Long,
): WaitStep = when (decision) {
    RingDecision.KEEP_WAITING -> WaitStep.Wait(allClearAtElapsed = null)
    RingDecision.RING_CLEAR -> {
        if (!sawAlert || pauseMinutes <= 0) {
            WaitStep.Ring(decision)
        } else {
            val since = allClearAtElapsed ?: nowElapsed
            if (nowElapsed - since >= pauseMinutes * 60_000L) WaitStep.Ring(decision) else WaitStep.Wait(since)
        }
    }
    else -> WaitStep.Ring(decision)
}
