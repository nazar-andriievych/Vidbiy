package ua.vidbiy.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ua.vidbiy.app.BuildConfig
import ua.vidbiy.app.R
import ua.vidbiy.app.alarm.nextTriggerAt
import ua.vidbiy.app.data.Alarm
import ua.vidbiy.app.data.PendingWait
import ua.vidbiy.app.data.SelectedRegion
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmListScreen(
    alarms: List<Alarm>,
    region: SelectedRegion?,
    waiting: PendingWait?,
    debugProxyUrl: String,
    onAdd: () -> Unit,
    onEdit: (Alarm) -> Unit,
    onToggle: (Alarm, Boolean) -> Unit,
    onCancelWaiting: (Long) -> Unit,
    onPickRegion: () -> Unit,
    onDebugProxyUrlChange: (String) -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.alarms_title)) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAdd) { Text(stringResource(R.string.action_add)) }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SystemWarnings(Modifier.fillMaxWidth()) }
            item { RegionCard(region = region, onClick = onPickRegion) }
            if (BuildConfig.DEBUG) {
                item {
                    DebugProxyCard(
                        url = debugProxyUrl,
                        onUrlChange = onDebugProxyUrlChange,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (alarms.isEmpty()) {
                item { EmptyState() }
            } else {
                items(alarms, key = { it.id }) { alarm ->
                    AlarmCard(
                        alarm = alarm,
                        waitingUntilMillis = waiting?.takeIf { it.alarmId == alarm.id }?.deadlineMillis,
                        onClick = { onEdit(alarm) },
                        onToggle = { onToggle(alarm, it) },
                        onCancelWaiting = { onCancelWaiting(alarm.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RegionCard(region: SelectedRegion?, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = stringResource(R.string.region_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = region?.title ?: stringResource(R.string.region_not_selected),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = region?.path?.takeIf { it.isNotEmpty() }
                    ?: stringResource(
                        if (region == null) R.string.region_hint_empty else R.string.region_hint
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.alarms_empty), style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(R.string.alarms_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AlarmCard(
    alarm: Alarm,
    waitingUntilMillis: Long?,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onCancelWaiting: () -> Unit,
) {
    // Поки триває очікування, картка живе окремим життям: будильник не вимкнений
    // і не «спрацює завтра» — він мовчить саме зараз і задзвонить після відбою.
    val deadline = remember(waitingUntilMillis) {
        waitingUntilMillis?.let {
            LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault())
        }
    }
    val waiting = deadline != null
    val subtleColor = if (waiting) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        onClick = onClick,
        colors = if (waiting) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatTime(alarm.hour, alarm.minute),
                    style = MaterialTheme.typography.displaySmall,
                )
                Text(
                    text = daysLabel(alarm),
                    style = MaterialTheme.typography.bodyMedium,
                    color = subtleColor,
                )
                if (deadline != null) {
                    Text(
                        text = stringResource(R.string.waiting_card_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.waiting_card_deadline, formatTime(deadline)),
                        style = MaterialTheme.typography.bodySmall,
                        color = subtleColor,
                    )
                } else if (alarm.enabled) {
                    val now = LocalDateTime.now()
                    Text(
                        text = stringResource(
                            R.string.next_trigger,
                            durationLabel(now, alarm.nextTriggerAt(now)),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = subtleColor,
                    )
                }
                if (alarm.respectAlerts && !waiting) {
                    Text(
                        text = stringResource(R.string.respect_alerts),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (waiting) {
                // Перемикач тут означав би «вимкнути будильник назавжди», а потрібне
                // інше: не дзвонити сьогодні. Наступні дні лишаються як були.
                TextButton(onClick = onCancelWaiting) {
                    Text(stringResource(R.string.waiting_card_skip))
                }
            } else {
                Switch(checked = alarm.enabled, onCheckedChange = onToggle)
            }
        }
    }
}
