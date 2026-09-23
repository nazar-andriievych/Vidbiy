package ua.vidbiy.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import ua.vidbiy.app.R
import ua.vidbiy.app.data.Alarm
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

fun formatTime(hour: Int, minute: Int): String = LocalTime.of(hour, minute).format(timeFormatter)

fun formatTime(at: LocalDateTime): String = at.format(timeFormatter)

/** «Щодня», «Будні», «Пн, Ср, Пт» або «Одноразово». */
@Composable
fun daysLabel(alarm: Alarm): String {
    val names = stringArrayResource(R.array.day_short_names)
    return when {
        alarm.days.isEmpty() -> stringResource(R.string.days_once)
        alarm.days.size == 7 -> stringResource(R.string.days_everyday)
        alarm.days == WEEKDAYS -> stringResource(R.string.days_weekdays)
        alarm.days == WEEKEND -> stringResource(R.string.days_weekend)
        else -> alarm.days.sorted().joinToString(", ") { names[it - 1] }
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
