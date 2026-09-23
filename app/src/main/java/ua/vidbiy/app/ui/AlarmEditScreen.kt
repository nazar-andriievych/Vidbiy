package ua.vidbiy.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import ua.vidbiy.app.R
import ua.vidbiy.app.data.Alarm
import java.time.LocalTime

private val MAX_WAIT_OPTIONS = listOf(30, 60, 120, 180)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditScreen(
    alarm: Alarm,
    onChange: ((Alarm) -> Alarm) -> Unit,
    onSave: (hour: Int, minute: Int) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    BackHandler(onBack = onCancel)

    // Стан пікера прив’язаний до конкретного будильника: інший id — інший початковий час.
    key(alarm.id) {
        val timeState = rememberTimePickerState(
            initialHour = alarm.hour,
            initialMinute = alarm.minute,
            is24Hour = true,
        )

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(
                                if (alarm.id == Alarm.NEW_ID) R.string.alarm_new_title
                                else R.string.alarm_edit_title
                            )
                        )
                    },
                    navigationIcon = {
                        TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                    },
                    actions = {
                        TextButton(onClick = { onSave(timeState.hour, timeState.minute) }) {
                            Text(stringResource(R.string.action_save))
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TimePicker(state = timeState)
                }

                DaysRow(
                    selected = alarm.days,
                    onToggleDay = { day ->
                        onChange { current ->
                            val days =
                                if (day in current.days) current.days - day else current.days + day
                            current.copy(days = days)
                        }
                    },
                )

                HorizontalDivider()

                SettingSwitch(
                    title = stringResource(R.string.respect_alerts),
                    subtitle = stringResource(
                        if (alarm.respectAlerts) R.string.respect_alerts_on
                        else R.string.respect_alerts_off
                    ),
                    checked = alarm.respectAlerts,
                    onCheckedChange = { checked -> onChange { it.copy(respectAlerts = checked) } },
                )

                if (alarm.respectAlerts) {
                    MaxWaitSection(
                        alarm = alarm,
                        hour = timeState.hour,
                        minute = timeState.minute,
                        onSelect = { minutes -> onChange { it.copy(maxWaitMinutes = minutes) } },
                    )
                }

                HorizontalDivider()

                RingtoneRow(
                    uri = alarm.ringtoneUri,
                    onPicked = { picked -> onChange { it.copy(ringtoneUri = picked) } },
                )

                SettingSwitch(
                    title = stringResource(R.string.vibrate),
                    subtitle = null,
                    checked = alarm.vibrate,
                    onCheckedChange = { checked -> onChange { it.copy(vibrate = checked) } },
                )

                if (alarm.id != Alarm.NEW_ID) {
                    HorizontalDivider()
                    TextButton(onClick = onDelete) {
                        Text(
                            text = stringResource(R.string.action_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DaysRow(selected: Set<Int>, onToggleDay: (Int) -> Unit) {
    val names = stringArrayResource(R.array.day_short_names)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (day in 1..7) {
            FilterChip(
                selected = day in selected,
                onClick = { onToggleDay(day) },
                label = { Text(names[day - 1]) },
            )
        }
    }
}

@Composable
private fun MaxWaitSection(alarm: Alarm, hour: Int, minute: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.max_wait), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (minutes in MAX_WAIT_OPTIONS) {
                FilterChip(
                    selected = alarm.maxWaitMinutes == minutes,
                    onClick = { onSelect(minutes) },
                    label = { Text(maxWaitLabel(minutes)) },
                )
            }
        }
        val deadline = LocalTime.of(hour, minute).plusMinutes(alarm.maxWaitMinutes.toLong())
        Text(
            text = stringResource(
                R.string.max_wait_hint,
                formatTime(deadline.hour, deadline.minute),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun maxWaitLabel(minutes: Int): String = stringResource(
    when (minutes) {
        30 -> R.string.max_wait_30
        60 -> R.string.max_wait_60
        180 -> R.string.max_wait_180
        else -> R.string.max_wait_120
    }
)

@Composable
private fun RingtoneRow(uri: String?, onPicked: (String?) -> Unit) {
    val context = LocalContext.current
    val pickerTitle = stringResource(R.string.ringtone_picker_title)
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val picked = result.data?.let {
                    IntentCompat.getParcelableExtra(
                        it,
                        RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
                        Uri::class.java,
                    )
                }
                onPicked(picked?.toString())
            }
        }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { launcher.launch(ringtonePickerIntent(pickerTitle, uri)) }
            .padding(vertical = 8.dp),
    ) {
        Text(stringResource(R.string.ringtone), style = MaterialTheme.typography.titleSmall)
        Text(
            text = ringtoneTitle(context, uri),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun ringtonePickerIntent(title: String, current: String?): Intent =
    Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
        putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, title)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
        putExtra(
            RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
            current?.let(Uri::parse)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
        )
    }

private fun ringtoneTitle(context: Context, uri: String?): String {
    if (uri == null) return context.getString(R.string.ringtone_default)
    // Мелодію могли видалити — тоді просто показуємо типову назву.
    return runCatching { RingtoneManager.getRingtone(context, Uri.parse(uri))?.getTitle(context) }
        .getOrNull() ?: context.getString(R.string.ringtone_default)
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
