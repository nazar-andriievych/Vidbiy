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
import androidx.compose.ui.unit.dp
import ua.vidbiy.app.R
import ua.vidbiy.app.data.Place
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
            // In idle the row has no leading icon: the ⏻ button on the right already marks the action.
            // The check spinner sits around the cancel button on the right, so the text does not jump.
            if (state != OneShotRowState.IDLE && state != OneShotRowState.CHECKING) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    when (state) {
                        OneShotRowState.NO_ALERT -> RowIcon(R.drawable.ic_check)
                        OneShotRowState.ONLY_YELLOW -> Box(Modifier.size(12.dp).background(MaterialTheme.alertColors.yellow, CircleShape))
                        OneShotRowState.NO_DATA -> RowIcon(R.drawable.ic_cloud_off)
                        OneShotRowState.IDLE, OneShotRowState.CHECKING -> Unit
                    }
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
                OneShotRowState.CHECKING -> Box(Modifier.size(Dimens.TouchTarget), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(Dimens.TouchTarget), strokeWidth = 2.5.dp)
                    FilledTonalIconButton(
                        onClick = onCancelCheck,
                        modifier = Modifier.size(Dimens.TouchTarget - 8.dp),
                        colors = androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    ) {
                        Icon(painterResource(R.drawable.ic_close), stringResource(R.string.one_shot_cancel_check))
                    }
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
 * «Жовта тривога · Дім» і дії очікування (FR-18) — окремого екрана очікування немає.
 * Після відбою заголовок — стан, як у картці: «Відбій · задзвоню о 22:56». «Розбуджу після
 * відбою», коли відбій уже настав, читалося б як «тривога ще триває».
 */
@Composable
fun OneShotBanner(
    placeName: String?,
    status: WaitStatus?,
    pauseMinutes: Int,
    snoozeMinutes: Int,
    onSnooze: () -> Unit,
    onSkip: () -> Unit,
) {
    val title = when (status.phase) {
        WaitPhase.PAUSE -> stringResource(R.string.wait_strip_pause, formatClock(status!!.ringAtMillis!!))
        else -> if (pauseMinutes > 0) {
            stringResource(R.string.one_shot_banner_title_pause, pauseMinutes)
        } else {
            stringResource(R.string.one_shot_banner_title)
        }
    }
    WaitPanel(
        title = title,
        details = listOfNotNull(status?.level?.let { stringResource(it.titleRes) }, placeName),
        status = status,
        snoozeMinutes = snoozeMinutes,
        onSnooze = onSnooze,
        onSkip = onSkip,
        modifier = Modifier.padding(horizontal = Dimens.ScreenPadding),
    )
}
