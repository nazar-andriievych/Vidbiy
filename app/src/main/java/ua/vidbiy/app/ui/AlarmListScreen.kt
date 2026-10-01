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
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.vidbiy.app.R
import ua.vidbiy.app.data.Alarm
import ua.vidbiy.app.alarm.OneShot
import ua.vidbiy.app.data.AlertLevel
import ua.vidbiy.app.data.PendingWait
import ua.vidbiy.app.data.WaitStatus
import ua.vidbiy.app.ui.theme.alertColors
import ua.vidbiy.app.data.PlacesState
import ua.vidbiy.app.data.shortTitle
import ua.vidbiy.app.data.WaitFor
import ua.vidbiy.app.ui.theme.Dimens
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Вкладка «Будильники» (design-spec 3.1): заголовок → попередження → рядок разового режиму →
 * картки будильників.
 */
@Composable
fun AlarmsTab(
    alarms: List<Alarm>,
    places: PlacesState,
    waits: List<PendingWait>,
    waitStatuses: List<WaitStatus>,
    contentPadding: PaddingValues,
    onAdd: () -> Unit,
    onEdit: (Alarm) -> Unit,
    onToggle: (Alarm, Boolean) -> Unit,
    onOpenWaiting: (alarmId: Long) -> Unit,
    oneShotRow: OneShotRowState,
    onStartOneShot: () -> Unit,
    onCancelOneShotCheck: () -> Unit,
    onNeedPlace: () -> Unit,
    missingPermissions: List<Permission>,
    onOpenPermissions: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(Dimens.ListGap),
    ) {
        item {
            TabHeader(
                title = stringResource(R.string.alarms_title),
                addLabel = stringResource(R.string.action_new_alarm),
                onAdd = onAdd,
            )
        }
        if (missingPermissions.isNotEmpty()) {
            item {
                PermissionsBanner(
                    missing = missingPermissions,
                    onOpen = onOpenPermissions,
                    modifier = Modifier.padding(horizontal = Dimens.ScreenPadding),
                )
            }
        }

        // Порядок з design-spec 3.1: рядок разового режиму → картки. Стан очікування
        // показує сам елемент, що чекає: рядок режиму стає банером, картка — смугою.
        item {
            val oneShotWait = waits.firstOrNull { it.alarmId == OneShot.ONE_SHOT_ID }
            if (oneShotWait != null) {
                OneShotBanner(
                    placeName = oneShotWait.alarm?.let { placeName(it, places) } ?: places.primary?.name,
                    status = waitStatuses.firstOrNull { it.alarmId == OneShot.ONE_SHOT_ID },
                    onOpen = { onOpenWaiting(OneShot.ONE_SHOT_ID) },
                )
            } else {
                OneShotRow(
                    state = oneShotRow,
                    primary = places.primary,
                    onStart = onStartOneShot,
                    onCancelCheck = onCancelOneShotCheck,
                    onNeedPlace = onNeedPlace,
                )
            }
        }

        if (alarms.isEmpty()) {
            item { EmptyAlarms() }
        } else {
            // Будильник, що чекає, — першим: інакше він міг би опинитися нижче видимої частини.
            val ordered = alarms.sortedByDescending { alarm -> waits.any { it.alarmId == alarm.id } }
            items(ordered, key = { it.id }) { alarm ->
                val wait = waits.firstOrNull { it.alarmId == alarm.id }
                AlarmCard(
                    alarm = alarm,
                    // Поки чекає — місце з налаштувань, з якими почалося очікування.
                    placeName = placeName(wait?.alarm ?: alarm, places),
                    wait = wait,
                    waitStatus = waitStatuses.firstOrNull { wait != null && it.alarmId == alarm.id },
                    onClick = { onEdit(alarm) },
                    onToggle = { onToggle(alarm, it) },
                    onOpenWaiting = { onOpenWaiting(alarm.id) },
                )
            }
        }
    }
}

/** Назва місця, а якщо місця немає (обрано напряму чи видалено) — коротка назва регіону. */
fun placeName(alarm: Alarm, places: PlacesState): String? =
    places.byId(alarm.placeId)?.name ?: alarm.region?.shortTitle

/**
 * Смуга очікування в картці будильника (design-spec 2): контейнер кольору рівня, крапка,
 * «Чекає відбою», «Червона тривога · Дім», шеврон → екран очікування.
 */
@Composable
private fun WaitingStrip(placeName: String?, status: WaitStatus?, onOpen: () -> Unit) {
    val now = rememberNowMillis()
    val colors = MaterialTheme.alertColors
    val phase = status.phase
    val level = status?.level
    val (container, content) = when {
        phase == WaitPhase.PAUSE -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        level == AlertLevel.RED -> colors.redContainer to colors.onRedContainer
        level == AlertLevel.YELLOW -> colors.yellowContainer to colors.onYellowContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurface
    }
    val title = when (phase) {
        WaitPhase.ALERT -> stringResource(R.string.wait_strip_alert)
        WaitPhase.PAUSE -> stringResource(R.string.wait_strip_pause, formatClock(status!!.ringAtMillis!!))
        WaitPhase.CHECKING -> stringResource(R.string.wait_strip_checking)
    }
    // «Оновлено …» — лише коли дані застаріли (design-spec 0, п. 4); крайній час — на екрані очікування.
    val updated = status?.confirmedAtMillis
        ?.takeIf { showsStaleWarning(it, now) }
        ?.let { stringResource(R.string.banner_updated_ago, ((now - it) / 60_000L).toInt()) }
    val subtitle = listOfNotNull(
        level?.let { stringResource(if (it == AlertLevel.RED) R.string.level_red else R.string.level_yellow) },
        placeName,
        updated,
    ).joinToString(" · ")

    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                level != null && phase == WaitPhase.ALERT -> Box(
                    Modifier.size(12.dp).background(if (level == AlertLevel.RED) colors.red else colors.yellow, CircleShape),
                )
                phase == WaitPhase.PAUSE -> Icon(painterResource(R.drawable.ic_schedule), null, Modifier.size(20.dp))
                else -> Icon(painterResource(R.drawable.ic_hourglass_top), null, Modifier.size(20.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null)
        }
    }
}

/**
 * Картка будильника (design-spec 2, `01-alarms--list`): час (і крайній час поруч, якщо заданий),
 * під ним один рядок — дні, місце й крапки рівня. Пауза — на екрані редагування. Поки будильник чекає
 * відбою ([wait]), світча немає (дії — на екрані очікування), а під часом — смуга очікування.
 */
@Composable
private fun AlarmCard(
    alarm: Alarm,
    placeName: String?,
    wait: PendingWait?,
    waitStatus: WaitStatus?,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onOpenWaiting: () -> Unit,
) {
    // Щоб «Один раз · сьогодні» саме перейшло на «завтра», коли час будильника мине.
    val now = rememberNowMillis()
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row {
                        Text(
                            text = formatTime(alarm.hour, alarm.minute),
                            style = MaterialTheme.typography.displayMedium,
                            // Одноразовий будильник уже зняв «увімкнено», але поки чекає — він живий.
                            color = if (alarm.enabled || wait != null) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.alignByBaseline(),
                        )
                        // Крайній час — поруч із часом, дрібніше й сірим: «06:45 до 08:00».
                        val deadline = alarm.deadlineMinute?.takeIf { alarm.respectAlerts }
                        if (deadline != null) {
                            val time = formatTime(deadline / 60, deadline % 60)
                            val description = stringResource(R.string.card_deadline_description, time)
                            // Вузький екран чи великий шрифт — трохи зменшуємо, а не обрізаємо.
                            val style = MaterialTheme.typography.titleLarge
                            BasicText(
                                text = stringResource(R.string.card_deadline, time),
                                style = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                maxLines = 1,
                                softWrap = false,
                                autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = style.fontSize),
                                modifier = Modifier
                                    .alignByBaseline()
                                    .padding(start = 10.dp)
                                    .semantics { contentDescription = description },
                            )
                        }
                    }
                    CardSummary(
                        alarm = alarm,
                        days = daysLabel(alarm, LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault())),
                        // Поки чекає, місце й рівень показує смуга очікування.
                        placeName = placeName.takeIf { wait == null },
                    )
                }
                if (wait == null) Switch(checked = alarm.enabled, onCheckedChange = onToggle)
            }

            if (wait != null) WaitingStrip(placeName, waitStatus, onOpenWaiting)
        }
    }
}

/**
 * «Пн–Пт · 📍 Дім ●●». Звичайний будильник — лише дні. Крапки для TalkBack читаються
 * як «будь-яка тривога» / «лише червона»: колір не єдиний носій змісту (design-spec 1.1).
 */
@Composable
private fun CardSummary(alarm: Alarm, days: String, placeName: String?) {
    val subtle = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.bodyMedium
    val showAlerts = alarm.respectAlerts && (placeName != null || alarm.region == null)
    val levelDescription = stringResource(
        if (alarm.waitFor == WaitFor.RED_ONLY) R.string.card_level_red_only else R.string.card_level_any
    )
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Text(days, style = style, color = subtle, maxLines = 1)
        if (!showAlerts) return@Row
        Text(" · ", style = style, color = subtle)
        Icon(
            painter = painterResource(R.drawable.ic_location_on),
            contentDescription = null,
            tint = subtle,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = placeName ?: stringResource(R.string.card_no_region),
            style = style,
            color = subtle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp).weight(1f, fill = false),
        )
        if (alarm.region != null) {
            LevelDots(
                alarm.waitFor,
                Modifier.padding(start = 8.dp).semantics { contentDescription = levelDescription },
                size = 8,
            )
        }
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
