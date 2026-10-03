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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import java.time.LocalDate
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
                    // Дата й час, що вже минули, зберегти не можна — чому, каже підказка під часом.
                    TextButton(onClick = save, enabled = alarm.nextTriggerAt(LocalDateTime.now()) != null) {
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
            TimeWheels(
                alarm = alarm,
                onChange = onChange,
            )

            Column {
                RepeatHeader(
                    alarm = alarm,
                    onDateChange = { date ->
                        onChange { it.copy(date = date, days = if (date != null) emptySet() else it.days) }
                    },
                )
                DaysRow(
                    selected = alarm.days,
                    onToggleDay = { day ->
                        onChange { current ->
                            // Дні тижня й дата виключають одне одне: повтор знімає дату.
                            val days = if (day in current.days) current.days - day else current.days + day
                            current.copy(days = days, date = if (days.isEmpty()) current.date else null)
                        }
                    },
                )
            }

            AlertsCard(alarm, placeName, onChange, onPickRegion)

            // Мелодії й вібрації в макеті немає, але вони вже були в застосунку — лишаємо окремою карткою.
            SettingsCard {
                RingtoneRow(
                    uri = alarm.ringtoneUri,
                    onPicked = { picked -> onChange { it.copy(ringtoneUri = picked) } },
                )
                RowDivider()
                SettingRow(
                    title = stringResource(R.string.vibrate),
                    value = null,
                    onClick = { onChange { it.copy(vibrate = !it.vibrate) } },
                    trailing = {
                        Switch(
                            checked = alarm.vibrate,
                            onCheckedChange = { checked -> onChange { it.copy(vibrate = checked) } },
                        )
                    },
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
            text = {
                // null тут не буває: з минулими датою й часом «Зберегти» вимкнене.
                alarm.nextTriggerAt(LocalDateTime.now())?.let { next ->
                    Text(stringResource(R.string.edit_waiting_text, nextRingLabel(next)))
                }
            },
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
}

/**
 * Час одразу барабанами, як у годиннику Samsung, — без окремого діалогу. Коли будильник
 * спрацює, написано в рядку над днями (RepeatHeader).
 */
@Composable
private fun TimeWheels(alarm: Alarm, onChange: ((Alarm) -> Alarm) -> Unit) {
    val style = MaterialTheme.typography.displayMedium
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NumberWheel(
                count = 24,
                value = alarm.hour,
                onValueChange = { hour -> onChange { it.copy(hour = hour) } },
                description = stringResource(R.string.wheel_hours),
                width = 120.dp,
                visibleRows = 3,
                rowHeight = 72.dp,
                textStyle = style,
            )
            Text(":", style = style, modifier = Modifier.padding(horizontal = 12.dp))
            NumberWheel(
                count = 60,
                value = alarm.minute,
                onValueChange = { minute -> onChange { it.copy(minute = minute) } },
                description = stringResource(R.string.wheel_minutes),
                width = 120.dp,
                visibleRows = 3,
                rowHeight = 72.dp,
                textStyle = style,
            )
        }
        if (alarm.nextTriggerAt(LocalDateTime.now()) == null) {
            // Дата сьогодні, а час уже минув: «Зберегти» вимкнене, тут пояснюємо чому.
            Text(
                text = stringResource(R.string.time_passed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** «08:00» або «01:00 наступного дня», якщо крайній час не пізніший за час будильника (FR-6). */
@Composable
private fun deadlineValue(deadlineMinute: Int, alarmMinute: Int): String {
    val time = formatTime(deadlineMinute / 60, deadlineMinute % 60)
    return if (deadlineMinute > alarmMinute) time else stringResource(R.string.deadline_hint_next_day, time)
}

/**
 * Рядок над днями, як у годиннику Samsung: «Спрацює завтра о 07:00» (+ × для дати),
 * праворуч — календар. Календар — M3 DatePickerDialog; минулі дні в ньому недоступні.
 */
@Composable
private fun RepeatHeader(alarm: Alarm, onDateChange: (LocalDate?) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val subtle = MaterialTheme.colorScheme.onSurfaceVariant
    val date = alarm.date

    Row(modifier = Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val next = alarm.nextTriggerAt(LocalDateTime.now())
        FieldLabel(
            text = next?.let { stringResource(R.string.saved_next_ring, nextRingLabel(it)) }
                ?: date?.let { stringResource(R.string.date_value, formatShortDate(it)) }
                ?: stringResource(R.string.repeat_title),
            modifier = Modifier.weight(1f),
        )
        if (date != null) {
            IconButton(onClick = { onDateChange(null) }) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.date_clear), tint = subtle)
            }
        }
        IconButton(onClick = { picking = true }) {
            Icon(painterResource(R.drawable.ic_calendar_today), stringResource(R.string.date_set), tint = subtle)
        }
    }

    if (picking) {
        // Без дати календар відкривається на дні найближчого спрацювання.
        val suggested = date ?: alarm.copy(days = emptySet()).nextTriggerAt(LocalDateTime.now())?.toLocalDate()
        AlarmDatePickerDialog(
            initial = suggested ?: LocalDate.now(),
            onConfirm = onDateChange,
            onDismiss = { picking = false },
        )
    }
}

/**
 * Календар M3. Він рахує дати в мілісекундах UTC-півночі, тож переводимо через номер дня
 * (epoch day), а не через часовий пояс телефона — інакше дата з'їжджала б на добу.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmDatePickerDialog(initial: LocalDate, onConfirm: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val today = LocalDate.now()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.toEpochDay() * MILLIS_PER_DAY,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) =
                Math.floorDiv(utcTimeMillis, MILLIS_PER_DAY) >= today.toEpochDay()

            override fun isSelectableYear(year: Int) = year >= today.year
        },
    )
    val colors = DatePickerDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { onConfirm(LocalDate.ofEpochDay(Math.floorDiv(it, MILLIS_PER_DAY))) }
                    onDismiss()
                },
                enabled = state.selectedDateMillis != null,
            ) { Text(stringResource(R.string.action_done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        colors = colors,
    ) {
        DatePicker(state = state, colors = colors)
    }
}

private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

/** Картка «Враховувати тривоги»: перемикач і рядки регіону, рівня, паузи й крайнього часу. */
@Composable
private fun AlertsCard(
    alarm: Alarm,
    placeName: String?,
    onChange: ((Alarm) -> Alarm) -> Unit,
    onPickRegion: () -> Unit,
) {
    SettingsCard {
        SettingRow(
            title = stringResource(R.string.respect_alerts),
            value = null,
            onClick = { onChange { it.copy(respectAlerts = !it.respectAlerts) } },
            titleStyle = MaterialTheme.typography.titleMedium,
            trailing = {
                Switch(
                    checked = alarm.respectAlerts,
                    onCheckedChange = { checked -> onChange { it.copy(respectAlerts = checked) } },
                )
            },
        )
        if (!alarm.respectAlerts) return@SettingsCard

        RowDivider()
        val region = alarm.region
        SettingRow(
            title = stringResource(R.string.region_label),
            value = when {
                region == null -> stringResource(R.string.region_not_selected)
                placeName != null -> "$placeName · ${region.shortTitle}"
                else -> region.shortTitle
            },
            onClick = onPickRegion,
            trailing = {
                Icon(
                    painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        RowDivider()
        LevelSettingRow(alarm.waitFor) { waitFor -> onChange { it.copy(waitFor = waitFor) } }
        RowDivider()
        PauseSettingRow(alarm.pauseMinutes) { minutes -> onChange { it.copy(pauseMinutes = minutes) } }
        RowDivider()
        DeadlineRow(
            deadlineMinute = alarm.deadlineMinute,
            alarmMinute = alarm.hour * 60 + alarm.minute,
            onChange = { minute -> onChange { it.copy(deadlineMinute = minute) } },
        )
    }
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

    SettingRow(
        title = stringResource(R.string.deadline),
        value = deadlineMinute?.let { deadlineValue(it, alarmMinute) } ?: stringResource(R.string.deadline_none),
        onClick = { picking = true },
        trailing = if (deadlineMinute == null) null else {
            {
                IconButton(onClick = { onChange(null) }) {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        stringResource(R.string.deadline_clear),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )

    if (picking) {
        TimePickerDialog(
            title = stringResource(R.string.deadline),
            hint = stringResource(R.string.deadline_hint),
            // Порожнє поле пропонує час будильника + 2 год — лише як відправну точку.
            initialMinute = deadlineMinute ?: ((alarmMinute + 120) % (24 * 60)),
            onConfirm = onChange,
            onDismiss = { picking = false },
        )
    }
}

/**
 * Діалог вибору часу: два барабани «години : хвилини» у 24-годинному форматі, як у годиннику
 * Samsung (design-spec 2, «Вибір часу»). Циферблат M3 замінено: у 24-годинному режимі він
 * губив «після обіду» при зміні хвилин, а барабани не мають такої плутанини в принципі.
 */
@Composable
private fun TimePickerDialog(
    title: String,
    hint: String,
    initialMinute: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var hour by rememberSaveable { mutableIntStateOf(initialMinute / 60) }
    var minute by rememberSaveable { mutableIntStateOf(initialMinute % 60) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                FieldLabel(title, Modifier.padding(bottom = 12.dp))
                Box(modifier = Modifier.align(Alignment.CenterHorizontally), contentAlignment = Alignment.Center) {
                    // Смуга під вибраним рядком обох барабанів.
                    Surface(
                        modifier = Modifier.width(232.dp).height(WheelRowHeight),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {}
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NumberWheel(
                            count = 24,
                            value = hour,
                            onValueChange = { hour = it },
                            description = stringResource(R.string.wheel_hours),
                        )
                        Text(
                            text = ":",
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        NumberWheel(
                            count = 60,
                            value = minute,
                            onValueChange = { minute = it },
                            description = stringResource(R.string.wheel_minutes),
                        )
                    }
                }
                FieldHint(hint, Modifier.padding(top = 12.dp))
                Row(modifier = Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    TextButton(onClick = {
                        onConfirm(hour * 60 + minute)
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

    SettingRow(
        title = stringResource(R.string.ringtone),
        value = ringtoneTitle(context, uri),
        onClick = { launcher.launch(ringtonePickerIntent(pickerTitle, uri)) },
    )
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
