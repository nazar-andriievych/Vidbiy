package ua.vidbiy.app.alarm

import ua.vidbiy.app.data.Alarm
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Момент наступного спрацювання будильника після [now].
 * Для одноразового (без днів тижня) — у задану дату або, без дати, сьогодні чи завтра.
 * null — лише для дати, чий час уже минув: такий будильник більше ніколи не спрацює.
 */
fun Alarm.nextTriggerAt(now: LocalDateTime): LocalDateTime? {
    val time = LocalTime.of(hour, minute)
    val today = now.toLocalDate()
    if (days.isEmpty() && date != null) {
        return LocalDateTime.of(date, time).takeIf { it.isAfter(now) }
    }
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

/**
 * FR-6: крайній час, після якого будильник дзвонить, навіть якщо відбою немає.
 * Найближчий такий час після [triggerAt]: будильник 23:00 і крайній 01:00 — це 01:00 наступного дня.
 * null — крайнього часу немає.
 */
fun Alarm.deadlineAfter(triggerAt: LocalDateTime): LocalDateTime? {
    val minute = deadlineMinute ?: return null
    val todayAt = LocalDateTime.of(triggerAt.toLocalDate(), LocalTime.of(minute / 60, minute % 60))
    return if (todayAt.isAfter(triggerAt)) todayAt else todayAt.plusDays(1)
}
