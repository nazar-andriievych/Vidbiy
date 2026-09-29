package ua.vidbiy.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import ua.vidbiy.app.R
import ua.vidbiy.app.data.Alarm
import ua.vidbiy.app.data.WaitFor
import ua.vidbiy.app.ui.theme.Dimens

/**
 * Рядок налаштування «назва · значення» (design-spec 2), як у годиннику Samsung: значення —
 * акцентним кольором під назвою, варіанти й пояснення — у нижній панелі чи діалозі по натиску.
 * [leading] — значок перед значенням (крапки рівня); [trailing] — ×, Switch тощо.
 */
@Composable
fun SettingRow(
    title: String,
    value: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.SettingRowHeight)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = titleStyle)
            if (value != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    leading?.invoke()
                    Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        trailing?.invoke()
    }
}

/** Картка з рядками налаштування: як [SectionCard], але рядки йдуть щільно, через [RowDivider]. */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.padding(horizontal = Dimens.CardPadding, vertical = 8.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            }
            content()
        }
    }
}

@Composable
fun RowDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** Нижня панель вибору (design-spec 2): заголовок, варіанти, пояснення. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChoiceSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** Значення рівня для рядка: «Будь-яка» / «Лише червона» (крапки ставить сам рядок). */
@Composable
fun levelValue(waitFor: WaitFor): String =
    stringResource(if (waitFor == WaitFor.RED_ONLY) R.string.wait_for_red_only else R.string.wait_for_any)

/** Значення паузи для рядка: «Одразу» / «5 хв». */
@Composable
fun pauseValue(minutes: Int): String =
    if (minutes > 0) stringResource(R.string.snooze_option, minutes) else stringResource(R.string.pause_none)

/** Рядок «Чекати, поки триває» + нижня панель з двома варіантами й поясненнями. */
@Composable
fun LevelSettingRow(waitFor: WaitFor, onSelect: (WaitFor) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    SettingRow(
        title = stringResource(R.string.wait_for),
        value = levelValue(waitFor),
        onClick = { open = true },
        leading = { LevelDots(waitFor) },
    )
    if (open) {
        ChoiceSheet(title = stringResource(R.string.wait_for), onDismiss = { open = false }) {
            Column(Modifier.selectableGroup()) {
                for ((option, hint) in listOf(
                    WaitFor.RED_AND_YELLOW to R.string.wait_for_any_hint,
                    WaitFor.RED_ONLY to R.string.wait_for_red_only_hint,
                )) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = option == waitFor,
                                role = Role.RadioButton,
                                onClick = { onSelect(option); open = false },
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == waitFor, onClick = null)
                        Column(
                            modifier = Modifier.weight(1f).padding(start = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                LevelDots(option)
                                Text(levelValue(option), style = MaterialTheme.typography.titleSmall)
                            }
                            FieldHint(stringResource(hint))
                        }
                    }
                }
            }
        }
    }
}

/** Рядок «Після відбою» + нижня панель з чипами 0 … 30 хв. */
@Composable
fun PauseSettingRow(minutes: Int, onSelect: (Int) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    SettingRow(
        title = stringResource(R.string.pause_title),
        value = pauseValue(minutes),
        onClick = { open = true },
    )
    if (open) {
        ChoiceSheet(title = stringResource(R.string.pause_sheet_title), onDismiss = { open = false }) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (option in Alarm.PAUSE_OPTIONS) {
                    ChoiceChip(
                        text = option.toString(),
                        selected = option == minutes,
                        onClick = { onSelect(option); open = false },
                    )
                }
            }
            FieldHint(stringResource(R.string.pause_hint))
        }
    }
}
