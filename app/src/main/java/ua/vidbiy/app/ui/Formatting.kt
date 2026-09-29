package ua.vidbiy.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import ua.vidbiy.app.R
import ua.vidbiy.app.alarm.nextTriggerAt
import ua.vidbiy.app.data.Alarm
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

// Українська локаль явно: інтерфейс лише українською, навіть якщо телефон англійською.
private val uk: Locale = Locale.forLanguageTag("uk")
private val shortDateFormatter = DateTimeFormatter.ofPattern("EE, d MMM", uk)
private val shortDateYearFormatter = DateTimeFormatter.ofPattern("EE, d MMM yyyy", uk)
private val longDateFormatter = DateTimeFormatter.ofPattern("d MMMM", uk)
private val longDateYearFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", uk)

/** «пт, 9 жовт.»; рік — лише якщо не поточний. Для картки будильника. */
fun formatShortDate(date: LocalDate, today: LocalDate = LocalDate.now()): String =
    date.format(if (date.year == today.year) shortDateFormatter else shortDateYearFormatter)

/** «9 жовтня»; рік — лише якщо не поточний. Для речень «задзвонить 9 жовтня». */
fun formatLongDate(date: LocalDate, today: LocalDate = LocalDate.now()): String =
    date.format(if (date.year == today.year) longDateFormatter else longDateYearFormatter)

fun formatTime(hour: Int, minute: Int): String = LocalTime.of(hour, minute).format(timeFormatter)

fun formatTime(at: LocalDateTime): String = at.format(timeFormatter)

/**
 * «Щодня», «Пн–Пт», «Пн, Ср, Пт» або «Один раз». Одноразовий будильник каже ще й день:
 * «Один раз · завтра» / «Один раз · пт, 9 жовт.» — інакше пізно ввечері не видно, коли він
 * спрацює. Без [now] день знає лише будильник із заданою датою.
 */
@Composable
fun daysLabel(alarm: Alarm, now: LocalDateTime? = null): String {
    val names = stringArrayResource(R.array.day_short_names)
    return when {
        alarm.days.isEmpty() -> {
            // Дата, що вже минула, нічого не каже (такий будильник от-от вимкнеться сам).
            val date = when {
                now == null -> alarm.date
                alarm.date != null || alarm.enabled -> alarm.nextTriggerAt(now)?.toLocalDate()
                else -> null
            } ?: return stringResource(R.string.days_once)
            val today = now?.toLocalDate() ?: LocalDate.now()
            val day = when (date) {
                today -> stringResource(R.string.day_today)
                today.plusDays(1) -> stringResource(R.string.day_tomorrow)
                else -> formatShortDate(date, today)
            }
            stringResource(R.string.days_once_on, day)
        }
        alarm.days.size == 7 -> stringResource(R.string.days_everyday)
        alarm.days == WEEKDAYS -> stringResource(R.string.days_weekdays)
        alarm.days == WEEKEND -> stringResource(R.string.days_weekend)
        else -> alarm.days.sorted().joinToString(", ") { names[it - 1] }
    }
}

/**
 * «сьогодні о 08:00» / «завтра о 06:00» / «у понеділок о 06:00» / «9 жовтня о 06:00».
 * День тижня — лише в межах найближчих шести днів, де він однозначний; далі (будильник
 * із датою) — сама дата.
 */
@Composable
fun nextRingLabel(next: LocalDateTime, now: LocalDateTime = LocalDateTime.now()): String {
    val time = formatTime(next)
    val today = now.toLocalDate()
    return when (next.toLocalDate()) {
        today -> stringResource(R.string.next_ring_today, time)
        today.plusDays(1) -> stringResource(R.string.next_ring_tomorrow, time)
        in today.plusDays(7)..LocalDate.MAX -> stringResource(R.string.next_ring_day, formatLongDate(next.toLocalDate(), today), time)
        else -> stringResource(
            R.string.next_ring_day,
            stringArrayResource(R.array.day_on_names)[next.dayOfWeek.value - 1],
            time,
        )
    }
}

/** «7 год 20 хв» — скільки лишилося до [target]. */
@Composable
fun durationLabel(from: LocalDateTime, target: LocalDateTime): String {
    val minutes = Duration.between(from, target).toMinutes()
    val hours = minutes / 60
    val restMinutes = minutes % 60
    return when {
        minutes < 1 -> stringResource(R.string.duration_less_than_minute)
        hours == 0L -> stringResource(R.string.duration_minutes, restMinutes)
        restMinutes == 0L -> stringResource(R.string.duration_hours, hours)
        else -> stringResource(R.string.duration_hours_minutes, hours, restMinutes)
    }
}

val WEEKDAYS = setOf(1, 2, 3, 4, 5)
val WEEKEND = setOf(6, 7)
