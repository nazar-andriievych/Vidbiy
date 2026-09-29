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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerState
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.IntentCompat
import java.time.LocalDateTime
import ua.vidbiy.app.R
import ua.vidbiy.app.alarm.nextTriggerAt
import ua.vidbiy.app.data.Alarm
import ua.vidbiy.app.data.WaitFor
import ua.vidbiy.app.data.label
import ua.vidbiy.app.data.shortTitle
import ua.vidbiy.app.ui.theme.Dimens
import ua.vidbiy.app.ui.theme.alertColors

/**
 * Редагування / новий будильник (design-spec 3.2, `03-edit-alarm`).
 * [placeName] — назва місця, з якого взято регіон; null — регіон обрано напряму.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditScreen(
    alarm: Alarm,
    placeName: String?,
    onChange: ((Alarm) -> Alarm) -> Unit,
    onPickRegion: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    stopsWaiting: () -> Boolean = { false },
) {
    BackHandler(onBack = onCancel)
    var pickingTime by rememberSaveable { mutableStateOf(false) }
    var confirmingSave by rememberSaveable { mutableStateOf(false) }
    val save = { if (stopsWaiting()) confirmingSave = true else onSave() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(
                            if (alarm.id == Alarm.NEW_ID) R.string.alarm_new_title else R.string.alarm_edit_title
                        ),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.action_close_without_saving),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = save) {
                        Text(stringResource(R.string.action_save), style = MaterialTheme.typography.labelLarge)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenPadding)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TimeHeader(alarm.hour, alarm.minute, onClick = { pickingTime = true })

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FieldLabel(stringResource(R.string.repeat_title), Modifier.padding(horizontal = 4.dp))
                DaysRow(
                    selected = alarm.days,
                    onToggleDay = { day ->
                        onChange { current ->
                            current.copy(days = if (day in current.days) current.days - day else current.days + day)
                        }
                    },
                )
                FieldHint(repeatSummary(alarm), Modifier.padding(horizontal = 4.dp))
            }

            AlertsCard(alarm, placeName, onChange, onPickRegion)

            // Мелодії й вібрації в макеті немає, але вони вже були в застосунку — лишаємо окремою карткою.
            SectionCard {
                RingtoneRow(
                    uri = alarm.ringtoneUri,
                    onPicked = { picked -> onChange { it.copy(ringtoneUri = picked) } },
                )
                SettingSwitch(
                    title = stringResource(R.string.vibrate),
                    checked = alarm.vibrate,
                    onCheckedChange = { checked -> onChange { it.copy(vibrate = checked) } },
                )
            }

            if (alarm.id != Alarm.NEW_ID) {
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.alertColors.red),
                ) {
                    Icon(painterResource(R.drawable.ic_delete), contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(
                        text = stringResource(R.string.alarm_delete),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }

    if (confirmingSave) {
        // FR-7b: зміни в будильнику, що чекає, припиняють очікування — кажемо про це прямо,
        // разом із тим, коли він задзвонить наступного разу (може статися, що вже завтра).
        AlertDialog(
            onDismissRequest = { confirmingSave = false },
            title = { Text(stringResource(R.string.edit_waiting_title)) },
            text = { Text(stringResource(R.string.edit_waiting_text, nextRingLabel(alarm))) },
            confirmButton = {
                TextButton(onClick = { confirmingSave = false; onSave() }) {
                    Text(stringResource(R.string.edit_waiting_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingSave = false }) { Text(stringResource(R.string.action_cancel)) }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }

    if (pickingTime) {
        TimePickerDialog(
            title = stringResource(R.string.time_dialog_title),
            initialMinute = alarm.hour * 60 + alarm.minute,
            onConfirm = { minute -> onChange { it.copy(hour = minute / 60, minute = minute % 60) } },
            onDismiss = { pickingTime = false },
        )
    }
}

/** «сьогодні о 08:00» / «завтра о 06:00» / «пн о 06:00» — коли будильник задзвонить після збереження. */
@Composable
private fun nextRingLabel(alarm: Alarm): String {
    val now = LocalDateTime.now()
    val next = alarm.nextTriggerAt(now)
    val time = formatTime(next.hour, next.minute)
    return when (next.toLocalDate()) {
        now.toLocalDate() -> stringResource(R.string.next_ring_today, time)
        now.toLocalDate().plusDays(1) -> stringResource(R.string.next_ring_tomorrow, time)
        else -> stringResource(
            R.string.next_ring_day,
            stringArrayResource(R.array.day_short_names)[next.dayOfWeek.value - 1].lowercase(),
            time,
        )
    }
}

/** Час великим шрифтом + «Торкніться, щоб змінити час». */
@Composable
private fun TimeHeader(hour: Int, minute: Int, onClick: () -> Unit) {
    val hint = stringResource(R.string.time_hint)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = hint, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(formatTime(hour, minute), style = MaterialTheme.typography.displayLarge, maxLines = 1)
        FieldHint(hint)
    }
}

/** Картка «Враховувати тривоги» з регіоном, рівнем, паузою й крайнім часом. */
@Composable
private fun AlertsCard(
    alarm: Alarm,
    placeName: String?,
    onChange: ((Alarm) -> Alarm) -> Unit,
    onPickRegion: () -> Unit,
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.weight(1f).padding(end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(R.string.respect_alerts), style = MaterialTheme.typography.titleMedium)
                FieldHint(stringResource(R.string.respect_alerts_on))
            }
            Switch(
                checked = alarm.respectAlerts,
                onCheckedChange = { checked -> onChange { it.copy(respectAlerts = checked) } },
            )
        }

        if (!alarm.respectAlerts) {
            FieldHint(stringResource(R.string.respect_alerts_off))
            return@SectionCard
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        val region = alarm.region
        ValueRow(
            icon = R.drawable.ic_location_on,
            label = stringResource(R.string.region_label),
            value = placeName ?: region?.shortTitle ?: stringResource(R.string.region_not_selected),
            detail = when {
                region == null -> stringResource(R.string.region_not_selected_hint)
                placeName != null -> region.label
                else -> region.label.substringAfter(" · ", missingDelimiterValue = "").ifEmpty { null }
            },
            onClick = onPickRegion,
        ) {
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FieldLabel(stringResource(R.string.wait_for))
            LevelSelector(alarm.waitFor) { waitFor -> onChange { it.copy(waitFor = waitFor) } }
            FieldHint(
                stringResource(
                    if (alarm.waitFor == WaitFor.RED_ONLY) R.string.wait_for_red_only_hint
                    else R.string.wait_for_any_hint
                )
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FieldLabel(stringResource(R.string.pause_title))
            PauseChips(alarm.pauseMinutes) { minutes -> onChange { it.copy(pauseMinutes = minutes) } }
            FieldHint(stringResource(R.string.pause_hint))
        }

        DeadlineRow(
            deadlineMinute = alarm.deadlineMinute,
            alarmMinute = alarm.hour * 60 + alarm.minute,
            onChange = { minute -> onChange { it.copy(deadlineMinute = minute) } },
        )
    }
}

/** «Чекати після відбою, хв»: 0, 2, 5, 10, 15, 30 (design-spec 3.2). */
@Composable
fun PauseChips(selected: Int, onSelect: (Int) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (minutes in Alarm.PAUSE_OPTIONS) {
            ChoiceChip(text = minutes.toString(), selected = selected == minutes, onClick = { onSelect(minutes) })
        }
    }
}

/** Сегменти «●● Будь-яка» / «● Лише червона» (design-spec 2). */
@Composable
fun LevelSelector(selected: WaitFor, onSelect: (WaitFor) -> Unit) {
    val options = listOf(WaitFor.RED_AND_YELLOW, WaitFor.RED_ONLY)
    SegmentedRow(
        segments = listOf(
            Segment(stringResource(R.string.wait_for_any)) { LevelDots(WaitFor.RED_AND_YELLOW) },
            Segment(stringResource(R.string.wait_for_red_only)) { LevelDots(WaitFor.RED_ONLY) },
        ),
        selectedIndex = options.indexOf(selected),
        onSelect = { onSelect(options[it]) },
    )
}

/** «Щобудня», «У вихідні», «Щодня», «Без повторів — задзвонить один раз» або перелік днів. */
@Composable
private fun repeatSummary(alarm: Alarm): String = when {
    alarm.days.isEmpty() -> stringResource(R.string.repeat_once)
    alarm.days == WEEKDAYS -> stringResource(R.string.repeat_weekdays)
    alarm.days == WEEKEND -> stringResource(R.string.repeat_weekend)
    else -> daysLabel(alarm)
}

/** «Повторювати»: сім круглих чипів (design-spec 2, «Чипи вибору»). */
@Composable
private fun DaysRow(selected: Set<Int>, onToggleDay: (Int) -> Unit) {
    val names = stringArrayResource(R.array.day_short_names)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (day in 1..7) {
            ChoiceChip(text = names[day - 1], selected = day in selected, onClick = { onToggleDay(day) })
        }
    }
}

/** FR-6: абсолютний крайній час; за замовчуванням не заданий. */
@Composable
private fun DeadlineRow(deadlineMinute: Int?, alarmMinute: Int, onChange: (Int?) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val subtle = MaterialTheme.colorScheme.onSurfaceVariant
    val time = deadlineMinute?.let { formatTime(it / 60, it % 60) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ValueRow(
            icon = R.drawable.ic_schedule,
            label = stringResource(R.string.deadline),
            value = time ?: stringResource(R.string.deadline_none),
            onClick = { picking = true },
        ) {
            if (deadlineMinute == null) {
                IconButton(onClick = { picking = true }) {
                    Icon(painterResource(R.drawable.ic_add), stringResource(R.string.deadline_set), tint = subtle)
                }
            } else {
                IconButton(onClick = { onChange(null) }) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.deadline_clear), tint = subtle)
                }
            }
        }
        FieldHint(
            text = when {
                time == null -> stringResource(R.string.deadline_none_hint)
                deadlineMinute > alarmMinute -> stringResource(R.string.deadline_hint, time)
                else -> stringResource(R.string.deadline_hint_next_day, time)
            },
            modifier = Modifier.padding(start = Dimens.IconCircle + 16.dp),
        )
    }

    if (picking) {
        TimePickerDialog(
            title = stringResource(R.string.deadline),
            // Порожнє поле пропонує час будильника + 2 год — лише як відправну точку.
            initialMinute = deadlineMinute ?: ((alarmMinute + 120) % (24 * 60)),
            onConfirm = onChange,
            onDismiss = { picking = false },
        )
    }
}

/**
 * Діалог вибору часу: M3 TimePicker / TimeInput у 24-годинному форматі з перемикачем
 * «циферблат ↔ клавіатура» (design-spec 2, «Вибір часу»).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    title: String,
    initialMinute: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initialMinute / 60,
        initialMinute = initialMinute % 60,
        is24Hour = true,
    )
    var keyboard by rememberSaveable { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                FieldLabel(title, Modifier.padding(bottom = 20.dp))
                Box(modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    if (keyboard) VidbiyTimeInput(state) else VidbiyTimePicker(state)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { keyboard = !keyboard }) {
                        Icon(
                            painterResource(if (keyboard) R.drawable.ic_schedule else R.drawable.ic_keyboard),
                            contentDescription = stringResource(
                                if (keyboard) R.string.time_input_dial else R.string.time_input_keyboard
                            ),
                        )
                    }
                    Box(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    TextButton(onClick = {
                        onConfirm(state.hour * 60 + state.minute)
                        onDismiss()
                    }) { Text(stringResource(R.string.action_done)) }
                }
            }
        }
    }
}

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
            .clickable { launcher.launch(ringtonePickerIntent(pickerTitle, uri)) },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(stringResource(R.string.ringtone), style = MaterialTheme.typography.titleSmall)
        FieldHint(ringtoneTitle(context, uri))
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
private fun SettingSwitch(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * M3 TimePicker малює години й хвилини стилем displayLarge, а в нашій темі це 88 sp
 * для великого часу — цифри не влазять. Дизайн дає для полів вибору часу displaySmall (52).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VidbiyTimePicker(state: TimePickerState) {
    val typography = MaterialTheme.typography
    MaterialTheme(typography = typography.copy(displayLarge = typography.displaySmall)) {
        TimePicker(state = state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VidbiyTimeInput(state: TimePickerState) {
    val typography = MaterialTheme.typography
    MaterialTheme(typography = typography.copy(displayLarge = typography.displaySmall)) {
        TimeInput(state = state)
    }
}
