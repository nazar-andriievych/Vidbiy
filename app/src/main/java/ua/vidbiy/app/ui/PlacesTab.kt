package ua.vidbiy.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
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
import ua.vidbiy.app.data.SelectedRegion
import ua.vidbiy.app.ui.theme.Dimens

/**
 * Вкладка «Мої місця». ТИМЧАСОВО: поки «Мої місця» (FR-26) не зроблені, тут один регіон
 * на весь застосунок, як і раніше. Список місць за design-spec 3.4 замінить цю картку.
 */
@Composable
fun PlacesTab(
    region: SelectedRegion?,
    contentPadding: PaddingValues,
    onPickRegion: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(Dimens.ListGap),
    ) {
        item { TabHeader(stringResource(R.string.places_title)) }
        item {
            Surface(
                onClick = onPickRegion,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(
                    modifier = Modifier.padding(Dimens.CardPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = region?.title ?: stringResource(R.string.region_not_selected),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = region?.path?.takeIf { it.isNotEmpty() }
                                ?: stringResource(
                                    if (region == null) R.string.region_hint_empty else R.string.region_hint
                                ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}
