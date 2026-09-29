package ua.vidbiy.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ua.vidbiy.app.R
import ua.vidbiy.app.data.Alarm
import ua.vidbiy.app.data.AlertLevel
import ua.vidbiy.app.data.PendingWait
import ua.vidbiy.app.data.WaitStatus
import ua.vidbiy.app.ui.theme.Dimens
import ua.vidbiy.app.ui.theme.alertColors
import java.time.Instant
import java.time.ZoneId

/** Стан очікування для показу. */
enum class WaitPhase { CHECKING, ALERT, PAUSE }

val WaitStatus?.phase: WaitPhase
    get() = when {
        this?.allClearAtMillis != null -> WaitPhase.PAUSE
        this?.level != null -> WaitPhase.ALERT
        else -> WaitPhase.CHECKING
    }

/**
 * FR-15 [дизайн]: попередження «Немає зв'язку» — коли даним понад 120 с.
 *
 * Не менше: сервер підтверджує стан раз на 60 с, телефон питає раз на 30 с, тож справні
 * дані бувають старими до ~90 с. З порогом 60 с попередження блимало щохвилини при живому
 * зв'язку (журнал рішень, 2026-09-29). 120 с — пропущено щонайменше одне оновлення, і до
 * дзвінка за FR-15 (180 с) лишається саме та хвилина, про яку пише попередження.
 */
const val STALE_WARNING_MILLIS = 120_000L

/** Чи показувати попередження «Немає зв'язку» ([confirmedAtMillis] — коли сервер підтвердив стан). */
fun showsStaleWarning(confirmedAtMillis: Long?, nowMillis: Long): Boolean =
    confirmedAtMillis != null && nowMillis - confirmedAtMillis > STALE_WARNING_MILLIS

/** Поточний час, що оновлюється раз на кілька секунд — для «оновлено 2 хв тому». */
@Composable
fun rememberNowMillis(periodMillis: Long = 5_000L): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(periodMillis)
            value = System.currentTimeMillis()
        }
    }
    return now
}

fun formatClock(millis: Long): String =
    formatTime(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDateTime())

/**
 * Екран очікування (design-spec 3.8, `10-waiting`): коло стану, заголовок, чип рівня,
 * причина, план одним реченням. Внизу —
 * «Подзвони через X хв» і «Сьогодні не дзвони» з утриманням (FR-18).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaitingScreen(
    alarm: Alarm,
    placeName: String?,
    wait: PendingWait,
    status: WaitStatus?,
    snoozeMinutes: Int,
    onBack: () -> Unit,
    onSnooze: () -> Unit,
    onSkip: () -> Unit,
    oneShot: Boolean = false,
    justEnabled: Boolean = false,
) {
    BackHandler(onBack = onBack)
    val now = rememberNowMillis()
    val phase = status.phase
    val time = formatTime(alarm.hour, alarm.minute)
    val confirmedAt = status?.confirmedAtMillis
    val stale = showsStaleWarning(confirmedAt, now)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (oneShot) {
                            stringResource(R.string.waiting_screen_title_one_shot, placeName.orEmpty())
                        } else if (placeName != null) {
                            stringResource(R.string.waiting_screen_title, time, placeName)
                        } else {
                            stringResource(R.string.waiting_screen_title_no_place, time)
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = Dimens.ScreenPadding, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilledTonalButton(
                    onClick = onSnooze,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.ButtonHeight),
                ) {
                    Text(stringResource(R.string.waiting_snooze, snoozeMinutes), style = MaterialTheme.typography.labelLarge)
                }
                HoldButton(
                    text = stringResource(R.string.waiting_skip),
                    hint = stringResource(R.string.waiting_skip_hint),
                    icon = R.drawable.ic_notifications_off,
                    onConfirmed = onSkip,
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenPadding, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (justEnabled) EnabledNote()
            StateCircle(phase, status?.level)
            Text(
                text = when (phase) {
                    WaitPhase.ALERT -> stringResource(R.string.waiting_state_alert)
                    WaitPhase.PAUSE -> stringResource(R.string.waiting_state_pause, formatClock(status!!.allClearAtMillis!!))
                    WaitPhase.CHECKING -> stringResource(R.string.waiting_state_checking)
                },
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            when (phase) {
                WaitPhase.ALERT -> LevelChip(status!!.level!!)
                WaitPhase.PAUSE -> StatusChip(
                    text = stringResource(R.string.waiting_chip_pause, alarm.pauseMinutes),
                    container = MaterialTheme.colorScheme.primaryContainer,
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                WaitPhase.CHECKING -> Unit
            }

            // Причина — дрібно під чипом; «Оновлено» — лише в попередженні, коли дані застаріли;
            // крайній час — у плані (design-spec 3.8).
            status?.reason?.let { reason ->
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }

            if (stale && confirmedAt != null) {
                StaleWarning(confirmedAt)
            } else {
                val deadline = wait.deadlineMillis?.let(::formatClock)
                val plan = when {
                    phase == WaitPhase.PAUSE -> stringResource(R.string.waiting_plan_in_pause, formatClock(status!!.ringAtMillis!!))
                    alarm.pauseMinutes > 0 && deadline != null ->
                        stringResource(R.string.waiting_plan_pause_deadline, alarm.pauseMinutes, deadline)
                    alarm.pauseMinutes > 0 -> stringResource(R.string.waiting_plan_pause, alarm.pauseMinutes)
                    deadline != null -> stringResource(R.string.waiting_plan_now_deadline, deadline)
                    else -> stringResource(R.string.waiting_plan_now)
                }
                Text(
                    text = plan,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

/** «✓ Увімкнено · Можна спати — розбуджу після відбою» (`09-one-shot--2`). */
@Composable
private fun EnabledNote() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(22.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.one_shot_enabled_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.one_shot_enabled_text), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Коло 104 у кольорі стану з ореолом 8 (design-spec 3.8). */
@Composable
private fun StateCircle(phase: WaitPhase, level: AlertLevel?) {
    val colors = MaterialTheme.alertColors
    val (container, content) = when {
        phase == WaitPhase.PAUSE -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        level == AlertLevel.RED -> colors.redContainer to colors.onRedContainer
        level == AlertLevel.YELLOW -> colors.yellowContainer to colors.onYellowContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(Dimens.StateCircle + 16.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(Dimens.StateCircle).background(container, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            when (phase) {
                WaitPhase.CHECKING -> CircularProgressIndicator(color = content, modifier = Modifier.size(40.dp))
                WaitPhase.PAUSE -> Icon(painterResource(R.drawable.ic_schedule), null, tint = content, modifier = Modifier.size(40.dp))
                WaitPhase.ALERT -> Icon(painterResource(R.drawable.ic_bedtime), null, tint = content, modifier = Modifier.size(40.dp))
            }
        }
    }
}

/** Чип рівня: «● Червона тривога · ракетна загроза». Колір не єдиний носій — поруч текст. */
@Composable
fun LevelChip(level: AlertLevel, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.alertColors
    StatusChip(
        text = stringResource(if (level == AlertLevel.RED) R.string.level_red_full else R.string.level_yellow_full),
        container = if (level == AlertLevel.RED) colors.redContainer else colors.yellowContainer,
        content = if (level == AlertLevel.RED) colors.onRedContainer else colors.onYellowContainer,
        dot = if (level == AlertLevel.RED) colors.red else colors.yellow,
        modifier = modifier,
    )
}

@Composable
private fun StatusChip(
    text: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    dot: Color? = null,
) {
    Surface(modifier = modifier.heightIn(min = Dimens.StatusChipHeight), shape = CircleShape, color = container) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (dot != null) Box(Modifier.size(10.dp).background(dot, CircleShape))
            Text(text, style = MaterialTheme.typography.labelSmall, color = content)
        }
    }
}

/** Жовтий блок «Немає зв'язку з сервісом» (стан `stale-data`). */
@Composable
private fun StaleWarning(confirmedAtMillis: Long) {
    val colors = MaterialTheme.alertColors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colors.yellowContainer,
        contentColor = colors.onYellowContainer,
    ) {
        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(R.drawable.ic_cloud_off), contentDescription = null, modifier = Modifier.size(22.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.waiting_stale_title, formatClock(confirmedAtMillis)), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.waiting_stale_text), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
