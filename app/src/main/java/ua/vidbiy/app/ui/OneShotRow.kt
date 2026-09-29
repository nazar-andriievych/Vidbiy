package ua.vidbiy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.vidbiy.app.R
import ua.vidbiy.app.data.AlertLevel
import ua.vidbiy.app.data.Place
import ua.vidbiy.app.data.WaitFor
import ua.vidbiy.app.data.WaitStatus
import ua.vidbiy.app.ui.theme.Dimens
import ua.vidbiy.app.ui.theme.alertColors

/** Стан рядка «Розбуди після відбою» (design-spec 4). */
enum class OneShotRowState { IDLE, CHECKING, NO_ALERT, ONLY_YELLOW, NO_DATA }

/**
 * Рядок разового режиму вгорі головного екрана (FR-25a): кнопка ⏻, перевірка тривоги
 * просто в рядку, короткі повідомлення «Зараз тривоги немає» / «Немає даних».
 */
@Composable
fun OneShotRow(
    state: OneShotRowState,
    primary: Place?,
    onStart: () -> Unit,
    onCancelCheck: () -> Unit,
    onNeedPlace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val place = primary?.name
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.heightIn(min = 72.dp).padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                when (state) {
                    OneShotRowState.CHECKING -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp)
                    OneShotRowState.NO_ALERT -> RowIcon(R.drawable.ic_check)
                    OneShotRowState.ONLY_YELLOW -> Box(Modifier.size(12.dp).background(MaterialTheme.alertColors.yellow, CircleShape))
                    OneShotRowState.NO_DATA -> RowIcon(R.drawable.ic_cloud_off)
                    OneShotRowState.IDLE -> RowIcon(R.drawable.ic_bedtime, MaterialTheme.colorScheme.primary)
                }
            }
            val (title, subtitle) = when (state) {
                // Рівень і пауза — у налаштуваннях; тут лише місце (design-spec 4).
                OneShotRowState.IDLE -> stringResource(R.string.one_shot_title) to (place ?: stringResource(R.string.one_shot_no_place))
                OneShotRowState.CHECKING ->
                    stringResource(R.string.one_shot_checking) to stringResource(R.string.one_shot_checking_sub, place.orEmpty())
                OneShotRowState.NO_ALERT ->
                    stringResource(R.string.one_shot_no_alert) to stringResource(R.string.one_shot_no_alert_sub, place.orEmpty())
                OneShotRowState.ONLY_YELLOW ->
                    stringResource(R.string.one_shot_only_yellow) to stringResource(R.string.one_shot_only_yellow_sub, place.orEmpty())
                OneShotRowState.NO_DATA ->
                    stringResource(R.string.one_shot_no_data) to stringResource(R.string.one_shot_no_data_sub)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                FieldHint(subtitle)
            }
            when (state) {
                OneShotRowState.CHECKING -> FilledTonalIconButton(
                    onClick = onCancelCheck,
                    modifier = Modifier.size(Dimens.TouchTarget),
                    colors = androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.one_shot_cancel_check))
                }
                OneShotRowState.NO_DATA -> FilledTonalButton(onClick = onStart) {
                    Text(stringResource(R.string.one_shot_retry), style = MaterialTheme.typography.labelLarge)
                }
                else -> FilledTonalIconButton(
                    onClick = if (primary == null) onNeedPlace else onStart,
                    modifier = Modifier.size(Dimens.TouchTarget),
                ) {
                    Icon(painterResource(R.drawable.ic_power_settings_new), stringResource(R.string.one_shot_enable))
                }
            }
        }
    }
}

@Composable
private fun RowIcon(icon: Int, tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
}

/**
 * Банер активного режиму (`09-one-shot--3-main-active-banner`): «Розбуджу після відбою»,
 * «Жовта тривога · Дім», шеврон → екран очікування.
 */
@Composable
fun OneShotBanner(
    placeName: String?,
    status: WaitStatus?,
    onOpen: () -> Unit,
) {
    val colors = MaterialTheme.alertColors
    val phase = status.phase
    val level = status?.level
    val (container, content) = when {
        phase == WaitPhase.PAUSE -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        level == AlertLevel.RED -> colors.redContainer to colors.onRedContainer
        level == AlertLevel.YELLOW -> colors.yellowContainer to colors.onYellowContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurface
    }
    val subtitle = when (phase) {
        WaitPhase.PAUSE -> listOfNotNull(
            stringResource(R.string.one_shot_banner_pause, formatClock(status!!.ringAtMillis!!)),
            placeName,
        )
        else -> listOfNotNull(
            level?.let { stringResource(if (it == AlertLevel.RED) R.string.level_red else R.string.level_yellow) },
            placeName,
        )
    }.joinToString(" · ")

    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding),
        shape = MaterialTheme.shapes.medium,
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 16.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when {
                level != null && phase == WaitPhase.ALERT -> Box(
                    Modifier.size(12.dp).background(if (level == AlertLevel.RED) colors.red else colors.yellow, CircleShape),
                )
                phase == WaitPhase.PAUSE -> Icon(painterResource(R.drawable.ic_schedule), null, Modifier.size(20.dp))
                else -> Icon(painterResource(R.drawable.ic_bedtime), null, Modifier.size(20.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.one_shot_banner_title), style = MaterialTheme.typography.titleSmall)
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null)
        }
    }
}
