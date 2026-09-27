package ua.vidbiy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.vidbiy.app.R
import ua.vidbiy.app.data.Alarm
import ua.vidbiy.app.data.PendingWait
import ua.vidbiy.app.data.PlacesState
import ua.vidbiy.app.data.shortTitle
import ua.vidbiy.app.data.WaitFor
import ua.vidbiy.app.ui.theme.Dimens

/**
 * Вкладка «Будильники» (design-spec 3.1): заголовок → попередження → банер очікування →
 * картки будильників. Рядок разового режиму з'явиться разом із самим режимом (FR-25a).
 */
@Composable
fun AlarmsTab(
    alarms: List<Alarm>,
    places: PlacesState,
    waiting: PendingWait?,
    contentPadding: PaddingValues,
    onEdit: (Alarm) -> Unit,
    onToggle: (Alarm, Boolean) -> Unit,
    onCancelWaiting: (Long) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(Dimens.ListGap),
    ) {
        item { TabHeader(stringResource(R.string.alarms_title)) }
        item { SystemWarnings(Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding)) }

        val waitingAlarm = waiting?.let { wait -> alarms.firstOrNull { it.id == wait.alarmId } }
        if (waitingAlarm != null) {
            item {
                WaitingBanner(
                    alarm = waitingAlarm,
                    placeName = placeName(waitingAlarm, places),
                    onCancel = { onCancelWaiting(waitingAlarm.id) },
                )
            }
        }

        if (alarms.isEmpty()) {
            item { EmptyAlarms() }
        } else {
            items(alarms, key = { it.id }) { alarm ->
                AlarmCard(
                    alarm = alarm,
                    placeName = placeName(alarm, places),
                    onClick = { onEdit(alarm) },
                    onToggle = { onToggle(alarm, it) },
                )
            }
        }
    }
}

/** Назва місця, а якщо місця немає (обрано напряму чи видалено) — коротка назва регіону. */
private fun placeName(alarm: Alarm, places: PlacesState): String? =
    places.byId(alarm.placeId)?.name ?: alarm.region?.shortTitle

/**
 * Банер «06:45 чекає на відбій тривоги». Повний екран очікування й колір рівня
 * з'являться разом з ним (design-spec 3.8); поки що звідси можна лише скасувати дзвінок.
 */
@Composable
private fun WaitingBanner(alarm: Alarm, placeName: String?, onCancel: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.waiting_banner_title, formatTime(alarm.hour, alarm.minute)),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (placeName != null) {
                    Text(
                        text = placeName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.waiting_card_skip)) }
        }
    }
}

/** Картка будильника (design-spec 2, `01-alarms--list`). */
@Composable
private fun AlarmCard(
    alarm: Alarm,
    placeName: String?,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier.weight(1f).clickable(onClick = onClick),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = formatTime(alarm.hour, alarm.minute),
                        style = MaterialTheme.typography.displayMedium,
                        color = if (alarm.enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        text = daysLabel(alarm),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = alarm.enabled, onCheckedChange = onToggle)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (alarm.respectAlerts) {
                AlertSummary(alarm, placeName, Modifier.clickable(onClick = onClick))
            } else {
                Text(
                    text = stringResource(R.string.card_plain),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** «📍 Дім ●● будь-яка тривога» + «Одразу після відбою · без крайнього часу». */
@Composable
private fun AlertSummary(alarm: Alarm, placeName: String?, modifier: Modifier = Modifier) {
    val subtle = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_location_on),
                contentDescription = null,
                tint = subtle,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = placeName ?: stringResource(R.string.card_no_region),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false),
            )
            LevelDots(alarm.waitFor, Modifier.padding(start = 14.dp, end = 8.dp))
            Text(
                text = stringResource(
                    if (alarm.waitFor == WaitFor.RED_ONLY) R.string.card_level_red_only
                    else R.string.card_level_any
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = subtle,
                maxLines = 1,
            )
        }
        val pause = if (alarm.pauseMinutes > 0) {
            stringResource(R.string.card_pause, alarm.pauseMinutes)
        } else {
            stringResource(R.string.card_right_after)
        }
        val deadline = alarm.deadlineMinute?.let { stringResource(R.string.card_deadline, formatTime(it / 60, it % 60)) }
            ?: stringResource(R.string.card_no_deadline)
        Text(
            text = "$pause · $deadline",
            style = MaterialTheme.typography.bodyMedium,
            color = subtle,
            modifier = Modifier.padding(start = 26.dp),
        )
    }
}

/** Порожній стан (`02-empty--alarms-empty`). */
@Composable
private fun EmptyAlarms() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_alarm),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(36.dp),
            )
        }
        Text(
            text = stringResource(R.string.alarms_empty),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.alarms_empty_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
