package ua.vidbiy.app.alarm

import ua.vidbiy.app.data.ActiveLevel
import ua.vidbiy.app.data.AlertLevel
import ua.vidbiy.app.data.AlertsSnapshot
import ua.vidbiy.app.data.SelectedRegion
import ua.vidbiy.app.data.WaitFor
import ua.vidbiy.app.data.alertUids

/** Чому будильник дзвонить (або чому й далі мовчить). */
enum class RingDecision {
    /** Тривоги потрібного рівня в регіоні немає — відбій або її й не було. */
    RING_CLEAR,

    /** Настав крайній час (FR-16). */
    RING_DEADLINE,

    /** Даних про тривогу немає взагалі: мережа, проксі або ukrainealarm мовчать. */
    RING_NO_DATA,

    /** Дані є, але старші за поріг — вірити їм не можна. */
    RING_STALE,

    /** Тривога триває понад добу: вона не рахується, будильник більше не чекає (FR-17, FR-27). */
    RING_ALERT_TOO_LONG,

    /**
     * Проксі каже, що ця версія застосунку застаріла: у ній відомий небезпечний баг, тож
     * тривогам вона не довіряє й дзвонить як звичайний будильник (docs/proxy-api.md).
     */
    RING_OUTDATED,

    /** Тривога триває, чекаємо відбою. */
    KEEP_WAITING,
    ;

    val shouldRing: Boolean get() = this != KEEP_WAITING
}

/** FR-31: дані, старші за 3 хвилини, вважаємо непридатними. */
const val MAX_DATA_AGE_SECONDS = 180L

/** FR-27: тривога, що почалася понад добу тому, не рахується. */
const val MAX_ALERT_AGE_MILLIS = 24 * 60 * 60 * 1000L

/**
 * Єдине місце, де вирішується «дзвонити чи чекати».
 *
 * Функція навмисно чиста: жодних годинників, мережі й Android усередині — усе приходить
 * аргументами. Саме тут живе fail-safe (NFR-1), тож ця логіка має бути перевірюваною
 * тестами до останньої гілки.
 *
 * [nowElapsed] — монотонний лічильник, для віку даних; [nowMillis] — годинник, лише для
 * правила 24 год: там похибка годинника в кілька хвилин нічого не важить.
 */
fun decideRing(
    snapshot: AlertsSnapshot?,
    nowElapsed: Long,
    nowMillis: Long,
    region: SelectedRegion?,
    waitFor: WaitFor,
    pastDeadline: Boolean,
): RingDecision {
    if (pastDeadline) return RingDecision.RING_DEADLINE
    if (snapshot?.outdated == true) return RingDecision.RING_OUTDATED
    // Регіон не обрано — чекати нема на що.
    if (region == null) return RingDecision.RING_NO_DATA

    val alerts = snapshot?.alerts ?: return RingDecision.RING_NO_DATA
    val age = snapshot.effectiveAgeSeconds(nowElapsed) ?: return RingDecision.RING_NO_DATA
    if (age > MAX_DATA_AGE_SECONDS) return RingDecision.RING_STALE

    val relevant = region.levelsOver(alerts).filter { waitFor.counts(it.level) }
    if (relevant.isEmpty()) return RingDecision.RING_CLEAR
    return if (relevant.any { nowMillis - it.sinceMillis < MAX_ALERT_AGE_MILLIS }) {
        RingDecision.KEEP_WAITING
    } else {
        RingDecision.RING_ALERT_TOO_LONG
    }
}

/**
 * FR-27: усі рівні, оголошені на територіях, що покривають обраний регіон (громада,
 * її район, її область). Разом вони й дають найвищий рівень для користувача.
 */
fun SelectedRegion.levelsOver(alerts: Map<String, List<ActiveLevel>>): List<ActiveLevel> =
    alertUids.flatMap { alerts[it].orEmpty() }

private fun WaitFor.counts(level: AlertLevel): Boolean = when (this) {
    WaitFor.RED_AND_YELLOW -> true
    WaitFor.RED_ONLY -> level == AlertLevel.RED
}

/**
 * Найвищий рівень, на який зараз чекає будильник (FR-27), — для показу на панелі очікування
 * і в сповіщенні. Серед однакових рівнів беремо той, де є текстова причина.
 */
fun SelectedRegion.strongestLevel(
    alerts: Map<String, List<ActiveLevel>>,
    waitFor: WaitFor,
    nowMillis: Long,
): ActiveLevel? = levelsOver(alerts)
    .filter { waitFor.counts(it.level) && nowMillis - it.sinceMillis < MAX_ALERT_AGE_MILLIS }
    .minWithOrNull(compareBy<ActiveLevel>({ it.level.ordinal }, { it.reason.isNullOrBlank() }))
