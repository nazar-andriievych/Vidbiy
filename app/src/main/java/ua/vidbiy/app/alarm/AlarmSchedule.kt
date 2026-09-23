package ua.vidbiy.app.alarm

import ua.vidbiy.app.data.Alarm
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Момент наступного спрацювання будильника після [now].
 * Для одноразового (без днів тижня) — сьогодні або завтра.
 */
fun Alarm.nextTriggerAt(now: LocalDateTime): LocalDateTime {
    val time = LocalTime.of(hour, minute)
    val today = now.toLocalDate()
    if (days.isEmpty()) {
        val todayAt = LocalDateTime.of(today, time)
        return if (todayAt.isAfter(now)) todayAt else todayAt.plusDays(1)
    }
    // Тиждень уперед вистачає: якщо сьогодні потрібний день, але час минув,
    // восьма ітерація дає той самий день наступного тижня.
    for (offset in 0..7) {
        val date = today.plusDays(offset.toLong())
        if (date.dayOfWeek.value !in days) continue
        val candidate = LocalDateTime.of(date, time)
        if (candidate.isAfter(now)) return candidate
    }
    error("Не вдалося знайти наступне спрацювання для будильника $id")
}

/** FR-7: крайній час, після якого будильник дзвонить, навіть якщо відбою немає. */
fun Alarm.deadlineAt(triggerAt: LocalDateTime): LocalDateTime =
    triggerAt.plusMinutes(maxWaitMinutes.toLong())
