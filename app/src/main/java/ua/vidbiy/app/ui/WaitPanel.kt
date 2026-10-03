package ua.vidbiy.app.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import ua.vidbiy.app.R
import ua.vidbiy.app.data.AlertLevel
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

/** «Червона тривога» / «Жовта тривога» — для рядка під заголовком панелі очікування. */
val AlertLevel.titleRes: Int
    @StringRes get() = if (this == AlertLevel.RED) R.string.level_red else R.string.level_yellow

fun formatClock(millis: Long): String =
    formatTime(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDateTime())

/**
 * Панель очікування (design-spec 2, FR-18) — у картці будильника й замість рядка разового режиму.
 * Окремого екрана очікування немає (рішення 2026-10-03): стан і дії — тут.
 *
 * Контейнер кольору рівня, крапка, [title] — стан разом з планом («Задзвоню через 5 хв після
 * відбою»), під ним [details] (рівень, місце). Третій рядок — лише те, чого немає вище: застереження
 * в паузі або попередження «Немає зв'язку». Внизу «Через X хв» і «Не дзвонити» з утриманням,
 * під ними на всю ширину — підказка після короткого натиску.
 */
@Composable
fun WaitPanel(
    title: String,
    details: List<String>,
    status: WaitStatus?,
    snoozeMinutes: Int,
    onSnooze: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
    val stale = showsStaleWarning(status?.confirmedAtMillis, now)
    var skipHint by remember { mutableStateOf(false) }
    val subtitle = details.joinToString(" · ")
    // Без «оновлено N хв тому» в рядку вище: «Немає зв'язку» каже те саме й показується за тієї ж умови.
    val note = when {
        stale -> stringResource(R.string.wait_note_stale)
        phase == WaitPhase.PAUSE -> stringResource(R.string.wait_note_in_pause)
        else -> null
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = container,
        contentColor = content,
    ) {
        Column(
            modifier = Modifier.padding(16.dp).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    level != null && phase == WaitPhase.ALERT -> Box(
                        Modifier.size(12.dp).background(if (level == AlertLevel.RED) colors.red else colors.yellow, CircleShape),
                    )
                    phase == WaitPhase.PAUSE -> Icon(painterResource(R.drawable.ic_schedule), null, Modifier.size(20.dp))
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    if (subtitle.isNotEmpty()) {
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (note != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (stale) Icon(painterResource(R.drawable.ic_cloud_off), null, Modifier.size(20.dp))
                    Text(note, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                val snoozeDescription = stringResource(R.string.wait_snooze_description, snoozeMinutes)
                Button(
                    onClick = onSnooze,
                    modifier = Modifier
                        .weight(1f)
                        .height(Dimens.TouchTarget)
                        .semantics { contentDescription = snoozeDescription },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    // Стандартні 24 dp з боків у картці будильника з'їдали «хв» у «Через 10 хв».
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    FittingLabel(stringResource(R.string.wait_snooze, snoozeMinutes), MaterialTheme.colorScheme.onSurface)
                }
                HoldButton(
                    text = stringResource(R.string.wait_skip),
                    hint = stringResource(R.string.wait_skip_hint),
                    onConfirmed = onSkip,
                    modifier = Modifier.weight(1f),
                    height = Dimens.TouchTarget,
                    // Під половинною кнопкою підказка ламалася б на два рядки — показуємо на всю ширину.
                    showHint = false,
                    onHintVisibleChange = { skipHint = it },
                )
            }
            AnimatedVisibility(visible = skipHint) {
                Text(
                    text = stringResource(R.string.wait_skip_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Підпис кнопки в один рядок: на вузькому екрані чи з великим шрифтом трохи зменшується,
 * а не обрізається (панель очікування ділить ширину на дві кнопки).
 */
@Composable
internal fun FittingLabel(text: String, color: Color) {
    val style = MaterialTheme.typography.labelLarge
    BasicText(
        text = text,
        style = style.copy(color = color, textAlign = TextAlign.Center),
        maxLines = 1,
        softWrap = false,
        autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = style.fontSize),
    )
}
