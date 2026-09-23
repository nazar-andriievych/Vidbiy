package ua.vidbiy.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ua.vidbiy.app.R
import ua.vidbiy.app.data.Oblast
import ua.vidbiy.app.data.Raion
import ua.vidbiy.app.data.RegionsCatalog
import ua.vidbiy.app.data.SelectedRegion
import ua.vidbiy.app.data.toSelection

/**
 * Вибір регіону: область → район → громада, з пошуком по всіх рівнях одразу.
 * Обрати можна будь-який рівень — тривога в ньому й відкладатиме будильник.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegionPickerScreen(onSelect: (SelectedRegion) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val catalog by produceState<RegionsCatalog?>(initialValue = null) {
        value = RegionsCatalog.load(context)
    }

    var query by rememberSaveable { mutableStateOf("") }
    var oblastUid by rememberSaveable { mutableStateOf<String?>(null) }
    var raionUid by rememberSaveable { mutableStateOf<String?>(null) }

    val oblast = catalog?.oblasts?.firstOrNull { it.uid == oblastUid }
    val raion = oblast?.raions?.firstOrNull { it.uid == raionUid }

    fun goBack() {
        when {
            raionUid != null -> raionUid = null
            oblastUid != null -> oblastUid = null
            else -> onCancel()
        }
    }

    BackHandler(onBack = ::goBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(raion?.title ?: oblast?.title ?: stringResource(R.string.region_title)) },
                navigationIcon = {
                    TextButton(onClick = ::goBack) {
                        Text(
                            stringResource(
                                if (oblastUid == null) R.string.action_cancel else R.string.action_back
                            )
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.region_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )

            val loaded = catalog
            if (loaded == null) {
                Loading()
                return@Column
            }

            when {
                query.isNotBlank() -> RegionList(
                    regions = loaded.search(query),
                    emptyText = stringResource(R.string.region_nothing_found),
                    onSelect = onSelect,
                )

                oblast == null -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(loaded.oblasts, key = { it.uid }) { item ->
                        // В області без районів (Київ, Севастополь, Крим) заглиблюватися нікуди.
                        RegionRow(
                            title = item.title,
                            onClick = {
                                if (item.raions.isEmpty()) onSelect(item.toSelection())
                                else oblastUid = item.uid
                            },
                        )
                    }
                }

                raion == null -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    item { WholeRegionButton(R.string.region_whole_oblast) { onSelect(oblast.toSelection()) } }
                    items(oblast.raions, key = { it.uid }) { item ->
                        RegionRow(title = item.title, onClick = { raionUid = item.uid })
                    }
                }

                else -> HromadaList(oblast = oblast, raion = raion, onSelect = onSelect)
            }
        }
    }
}

@Composable
private fun HromadaList(oblast: Oblast, raion: Raion, onSelect: (SelectedRegion) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item { WholeRegionButton(R.string.region_whole_raion) { onSelect(raion.toSelection(oblast)) } }
        items(raion.hromadas, key = { it.uid }) { item ->
            RegionRow(title = item.title, onClick = { onSelect(item.toSelection(oblast, raion)) })
        }
    }
}

@Composable
private fun RegionList(
    regions: List<SelectedRegion>,
    emptyText: String,
    onSelect: (SelectedRegion) -> Unit,
) {
    if (regions.isEmpty()) {
        Text(
            text = emptyText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        items(regions, key = { it.uid }) { region ->
            RegionRow(
                title = region.title,
                subtitle = region.path.takeIf { it.isNotEmpty() },
                onClick = { onSelect(region) },
            )
        }
    }
}

@Composable
private fun WholeRegionButton(textRes: Int, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(stringResource(textRes))
    }
}

@Composable
private fun RegionRow(title: String, subtitle: String? = null, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider()
}

@Composable
private fun Loading() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        CircularProgressIndicator()
        Text(stringResource(R.string.region_loading), style = MaterialTheme.typography.bodyMedium)
    }
}
