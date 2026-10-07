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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ua.vidbiy.app.BuildConfig
import ua.vidbiy.app.R
import ua.vidbiy.app.data.AppUpdate
import ua.vidbiy.app.data.Place
import ua.vidbiy.app.data.WaitFor
import ua.vidbiy.app.data.shortTitle
import androidx.compose.foundation.layout.Column
import ua.vidbiy.app.ui.theme.Dimens
import ua.vidbiy.app.ui.theme.ThemeMode

/**
 * Вкладка «Налаштування» (design-spec 3.6): рядки «назва · значення», варіанти — у нижніх
 * панелях; тема, дозволи й примітка про дані.
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
    appUpdate: AppUpdate? = null,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(Dimens.ListGap),
    ) {
        item { TabHeader(stringResource(R.string.settings_title)) }
        item {
            SettingsCard(modifier = Modifier.padding(horizontal = Dimens.ScreenPadding)) {
                SnoozeSettingRow(snoozeMinutes, onSnoozeChange)
            }
        }
        item {
            // «Розбуди після відбою» (design-spec 3.6): регіон — основне місце, рівень, пауза.
            SettingsCard(
                title = stringResource(R.string.one_shot_title),
                modifier = Modifier.padding(horizontal = Dimens.ScreenPadding),
            ) {
                SettingRow(
                    title = stringResource(R.string.one_shot_region_label),
                    value = primaryPlace?.let { "${it.name} · ${it.region.shortTitle}" }
                        ?: stringResource(R.string.region_not_selected),
                    onClick = onOpenPlaces,
                    trailing = {
                        Icon(
                            painterResource(R.drawable.ic_chevron_right),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                RowDivider()
                LevelSettingRow(oneShotWaitFor, onOneShotWaitFor)
                RowDivider()
                PauseSettingRow(oneShotPauseMinutes, onOneShotPause)
            }
        }
        item {
            SectionCard(
                title = stringResource(R.string.theme_title),
                modifier = Modifier.padding(horizontal = Dimens.ScreenPadding),
            ) {
                ThemeSelector(themeMode, onThemeModeChange)
            }
        }
        item { PermissionsRow(onOpen = onOpenPermissions, modifier = Modifier.padding(horizontal = Dimens.ScreenPadding)) }
        item { VersionRow(appUpdate, modifier = Modifier.padding(horizontal = Dimens.ScreenPadding)) }
        item { PrivacyNote() }
        if (BuildConfig.DEBUG) {
            item { DecisionLogCard(modifier = Modifier.padding(horizontal = Dimens.ScreenPadding)) }
        }
    }
}

/** design-spec 3.6: 5 / 10 / 15 / 20 / 30 хв. */
private val SNOOZE_OPTIONS = listOf(5, 10, 15, 20, 30)

/** Рядок «Відкласти дзвінок на · 10 хв» + нижня панель з варіантами (FR-19). */
@Composable
private fun SnoozeSettingRow(minutes: Int, onSelect: (Int) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    SettingRow(
        title = stringResource(R.string.snooze_title),
        value = stringResource(R.string.snooze_option, minutes),
        onClick = { open = true },
    )
    if (open) {
        ChoiceSheet(title = stringResource(R.string.snooze_title), onDismiss = { open = false }) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (option in SNOOZE_OPTIONS) {
                    SnoozeChip(option, selected = option == minutes, onClick = { onSelect(option); open = false })
                }
            }
            FieldHint(stringResource(R.string.snooze_hint))
        }
    }
}

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
