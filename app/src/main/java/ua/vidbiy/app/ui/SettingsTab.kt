package ua.vidbiy.app.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ua.vidbiy.app.BuildConfig
import ua.vidbiy.app.R
import ua.vidbiy.app.data.Place
import ua.vidbiy.app.data.WaitFor
import ua.vidbiy.app.data.label
import androidx.compose.foundation.layout.Column
import ua.vidbiy.app.ui.theme.Dimens
import ua.vidbiy.app.ui.theme.ThemeMode

/**
 * Вкладка «Налаштування» (design-spec 3.6). Поки що тут тема й примітка про дані;
 * відкладення, разовий режим і дозволи додаються разом із відповідними функціями.
 */
@Composable
fun SettingsTab(
    snoozeMinutes: Int,
    onSnoozeChange: (Int) -> Unit,
    primaryPlace: Place?,
    oneShotWaitFor: WaitFor,
    oneShotPauseMinutes: Int,
    onOneShotWaitFor: (WaitFor) -> Unit,
    onOneShotPause: (Int) -> Unit,
    onOpenPlaces: () -> Unit,
    onOpenPermissions: () -> Unit,
    themeMode: ThemeMode,
    contentPadding: PaddingValues,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(Dimens.ListGap),
    ) {
        item { TabHeader(stringResource(R.string.settings_title)) }
        item {
            SectionCard(
                title = stringResource(R.string.snooze_title),
                modifier = Modifier.padding(horizontal = Dimens.ScreenPadding),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (minutes in SNOOZE_OPTIONS) {
                        SnoozeChip(minutes, selected = minutes == snoozeMinutes, onClick = { onSnoozeChange(minutes) })
                    }
                }
                FieldHint(stringResource(R.string.snooze_hint, snoozeMinutes))
            }
        }
        item {
            // «Розбуди після відбою» (design-spec 3.6): регіон — основне місце, рівень, пауза.
            SectionCard(modifier = Modifier.padding(horizontal = Dimens.ScreenPadding)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.one_shot_title), style = MaterialTheme.typography.titleMedium)
                    FieldHint(stringResource(R.string.one_shot_settings_hint))
                }
                ValueRow(
                    icon = R.drawable.ic_location_on,
                    label = stringResource(R.string.one_shot_region_label),
                    value = primaryPlace?.name ?: stringResource(R.string.region_not_selected),
                    detail = primaryPlace?.region?.label,
                    onClick = onOpenPlaces,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_chevron_right),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FieldLabel(stringResource(R.string.wait_for))
                    LevelSelector(oneShotWaitFor, onOneShotWaitFor)
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FieldLabel(stringResource(R.string.pause_title))
                    PauseChips(oneShotPauseMinutes, onOneShotPause)
                    FieldHint(stringResource(R.string.one_shot_pause_hint))
                }
            }
        }
        item {
            SectionCard(
                title = stringResource(R.string.theme_title),
                modifier = Modifier.padding(horizontal = Dimens.ScreenPadding),
            ) {
                ThemeSelector(themeMode, onThemeModeChange)
                Text(
                    text = stringResource(R.string.theme_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { PermissionsRow(onOpen = onOpenPermissions, modifier = Modifier.padding(horizontal = Dimens.ScreenPadding)) }
        item { PrivacyNote() }
        if (BuildConfig.DEBUG) {
            item { DecisionLogCard(modifier = Modifier.padding(horizontal = Dimens.ScreenPadding)) }
        }
    }
}

/** design-spec 3.6: 5 / 10 / 15 / 20 / 30 хв. */
private val SNOOZE_OPTIONS = listOf(5, 10, 15, 20, 30)

/** Чип «10 хв»: як круглі чипи вибору, але ширший — у ньому два слова. */
@Composable
private fun SnoozeChip(minutes: Int, selected: Boolean, onClick: () -> Unit) {
    Surface(
        selected = selected,
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.height(Dimens.ChipHeight),
    ) {
        Box(modifier = Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.snooze_option, minutes), style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

private data class ThemeOption(val mode: ThemeMode, @StringRes val label: Int, @DrawableRes val icon: Int)

private val THEME_OPTIONS = listOf(
    ThemeOption(ThemeMode.System, R.string.theme_system, R.drawable.ic_smartphone),
    ThemeOption(ThemeMode.Light, R.string.theme_light, R.drawable.ic_light_mode),
    ThemeOption(ThemeMode.Dark, R.string.theme_dark, R.drawable.ic_dark_mode),
)

/** Сегментований перемикач (design-spec 2): висота 52, вибраний сегмент — secondaryContainer. */
@Composable
private fun ThemeSelector(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    SegmentedRow(
        segments = THEME_OPTIONS.map { option ->
            Segment(stringResource(option.label)) {
                Icon(painterResource(option.icon), contentDescription = null, modifier = Modifier.size(16.dp))
            }
        },
        selectedIndex = THEME_OPTIONS.indexOfFirst { it.mode == selected },
        onSelect = { onSelect(THEME_OPTIONS[it].mode) },
    )
}

@Composable
private fun PrivacyNote() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_info),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = stringResource(R.string.privacy_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
