package ua.vidbiy.app.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ua.vidbiy.app.BuildConfig
import ua.vidbiy.app.R
import ua.vidbiy.app.ui.theme.Dimens
import ua.vidbiy.app.ui.theme.ThemeMode

/**
 * Вкладка «Налаштування» (design-spec 3.6). Поки що тут тема й примітка про дані;
 * відкладення, разовий режим і дозволи додаються разом із відповідними функціями.
 */
@Composable
fun SettingsTab(
    themeMode: ThemeMode,
    debugProxyUrl: String,
    contentPadding: PaddingValues,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDebugProxyUrlChange: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(Dimens.ListGap),
    ) {
        item { TabHeader(stringResource(R.string.settings_title)) }
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
        item { PrivacyNote() }
        if (BuildConfig.DEBUG) {
            item {
                DebugProxyCard(
                    url = debugProxyUrl,
                    onUrlChange = onDebugProxyUrlChange,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding),
                )
            }
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
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        THEME_OPTIONS.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option.mode == selected,
                onClick = { onSelect(option.mode) },
                shape = SegmentedButtonDefaults.itemShape(index, THEME_OPTIONS.size),
                modifier = Modifier.height(Dimens.SegmentHeight),
                colors = SegmentedButtonDefaults.colors(
                    activeBorderColor = MaterialTheme.colorScheme.outline,
                    inactiveBorderColor = MaterialTheme.colorScheme.outline,
                ),
                icon = {
                    Icon(
                        painter = painterResource(option.icon),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
                label = { Text(stringResource(option.label), style = MaterialTheme.typography.labelLarge) },
            )
        }
    }
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
