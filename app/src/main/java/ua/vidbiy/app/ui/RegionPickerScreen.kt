package ua.vidbiy.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.vidbiy.app.R
import ua.vidbiy.app.data.Oblast
import ua.vidbiy.app.data.Place
import ua.vidbiy.app.data.RegionNames
import ua.vidbiy.app.data.RegionsCatalog
import ua.vidbiy.app.data.SelectedRegion
import ua.vidbiy.app.data.label
import ua.vidbiy.app.data.toSelection
import ua.vidbiy.app.ui.theme.Dimens

/** Що обрано: регіон і, якщо він узятий з «Моїх місць», саме місце. */
data class RegionPick(val region: SelectedRegion, val placeId: Long? = null)

/**
 * Вибір регіону (design-spec 3.3): пошук → «Мої місця» → «Інший регіон» з шляхом
 * «Усі області › область › район» → інфоблок про покриття → кнопка внизу.
 * Той самий екран служить для нового місця (крок 1) і для зміни регіону місця.
 *
 * [places] порожній — секції «Мої місця» немає, і список областей іде одразу.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegionPickerScreen(
    title: String,
    confirmLabel: String,
    initial: RegionPick?,
    onConfirm: (RegionPick) -> Unit,
    onBack: () -> Unit,
    places: List<Place> = emptyList(),
    primaryPlaceId: Long? = null,
    stepLabel: String? = null,
    currentLabel: String? = null,
    note: String? = null,
) {
    val context = LocalContext.current
    val catalog by produceState<RegionsCatalog?>(initialValue = null) {
        value = RegionsCatalog.load(context)
    }

    var query by rememberSaveable { mutableStateOf("") }
    var oblastUid by rememberSaveable { mutableStateOf<String?>(null) }
    var raionUid by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(initial) }

    // Регіон, обраний напряму, відкриваємо там, де він лежить: поруч сусіди, з якими
    // людина найімовірніше порівнює.
    LaunchedEffect(catalog) {
        val loaded = catalog ?: return@LaunchedEffect
        val start = initial?.takeIf { it.placeId == null || places.isEmpty() }?.region ?: return@LaunchedEffect
        if (oblastUid == null) {
            val (oblast, raion) = loaded.locate(start)
            oblastUid = oblast
            raionUid = raion
        }
    }

    fun goBack() {
        when {
            query.isNotEmpty() -> query = ""
            raionUid != null -> raionUid = null
            oblastUid != null -> oblastUid = null
            else -> onBack()
        }
    }
    BackHandler(onBack = ::goBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = ::goBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    if (stepLabel != null) {
                        Text(
                            text = stepLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 16.dp),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(Modifier.navigationBarsPadding()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Button(
                    onClick = { selected?.let(onConfirm) },
                    enabled = selected != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Dimens.ScreenPadding)
                        .heightIn(min = Dimens.ButtonHeight),
                ) {
                    Text(confirmLabel, style = MaterialTheme.typography.labelLarge)
                }
                }
            }
        },
    ) { padding ->
        val loaded = catalog
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
        ) {
            if (currentLabel != null) item { CurrentRegion(currentLabel) }
            item { SearchField(query = query, onQueryChange = { query = it }) }

            when {
                loaded == null -> item { Loading() }

                query.isNotBlank() -> searchResults(
                    results = loaded.search(query),
                    selected = selected,
                    onSelect = { selected = RegionPick(it) },
                )

                else -> {
                    if (places.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.region_my_places)) }
                        items(places, key = { "place-${it.id}" }) { place ->
                            val region = place.region.label
                            RadioRow(
                                title = place.name,
                                subtitle = if (place.id == primaryPlaceId) {
                                    stringResource(R.string.region_primary_suffix, region)
                                } else {
                                    region
                                },
                                selected = selected?.placeId == place.id,
                                bold = true,
                                onClick = { selected = RegionPick(place.region, place.id) },
                            )
                        }
                    }
                    val oblast = loaded.oblasts.firstOrNull { it.uid == oblastUid }
                    val raion = oblast?.raions?.firstOrNull { it.uid == raionUid }
                    when {
                        places.isNotEmpty() -> item { SectionHeader(stringResource(R.string.region_other)) }
                        oblast == null -> item { SectionHeader(stringResource(R.string.region_oblast_header)) }
                    }
                    if (oblast != null) {
                        item {
                            Breadcrumbs(
                                oblast = oblast,
                                raionTitle = raion?.title,
                                onAllOblasts = { oblastUid = null; raionUid = null },
                                onOblast = { raionUid = null },
                            )
                        }
                    }
                    val directPick = selected?.takeIf { it.placeId == null }?.region?.uid
                    when {
                        oblast == null -> items(RegionNames.oblastOrder(loaded.oblasts), key = { it.uid }) { item ->
                            // У м. Києві, Криму й Севастополі районів у довіднику немає — обирається одразу.
                            if (item.raions.isEmpty()) {
                                RadioRow(
                                    title = RegionNames.inOblastList(item.title),
                                    selected = directPick == item.uid,
                                    onClick = { selected = RegionPick(item.toSelection()) },
                                )
                            } else {
                                NavigateRow(
                                    title = RegionNames.inOblastList(item.title),
                                    onClick = { oblastUid = item.uid },
                                )
                            }
                        }

                        raion == null -> {
                            item(key = "whole-${oblast.uid}") {
                                RadioRow(
                                    title = stringResource(R.string.region_whole_oblast, oblast.title),
                                    selected = directPick == oblast.uid,
                                    bold = true,
                                    onClick = { selected = RegionPick(oblast.toSelection()) },
                                )
                            }
                            items(oblast.raions, key = { it.uid }) { item ->
                                NavigateRow(title = item.title, onClick = { raionUid = item.uid })
                            }
                        }

                        else -> {
                            item(key = "whole-${raion.uid}") {
                                RadioRow(
                                    title = stringResource(R.string.region_whole_raion, raion.title),
                                    selected = directPick == raion.uid,
                                    bold = true,
                                    onClick = { selected = RegionPick(raion.toSelection(oblast)) },
                                )
                            }
                            items(raion.hromadas, key = { it.uid }) { item ->
                                RadioRow(
                                    title = RegionNames.short(item.title),
                                    selected = directPick == item.uid,
                                    onClick = { selected = RegionPick(item.toSelection(oblast, raion)) },
                                )
                            }
                        }
                    }
                }
            }

            item {
                InfoNote(
                    text = note ?: stringResource(R.string.region_coverage_note),
                    modifier = Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = 16.dp),
                )
            }
        }
    }
}

/** Де в довіднику лежить регіон: UID області й району, щоб відкрити список на ньому. */
private fun RegionsCatalog.locate(region: SelectedRegion): Pair<String?, String?> {
    for (oblast in oblasts) {
        if (oblast.uid == region.uid) return oblast.uid to null
        for (raion in oblast.raions) {
            if (raion.uid == region.uid) return oblast.uid to null
            if (raion.hromadas.any { it.uid == region.uid }) return oblast.uid to raion.uid
        }
    }
    return null to null
}

private fun LazyListScope.searchResults(
    results: List<SelectedRegion>,
    selected: RegionPick?,
    onSelect: (SelectedRegion) -> Unit,
) {
    if (results.isEmpty()) {
        item {
            Text(
                text = stringResource(R.string.region_nothing_found),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
        }
        return
    }
    items(results, key = { "found-${it.uid}" }) { region ->
        val parents = region.label.substringAfter(" · ", missingDelimiterValue = "")
        RadioRow(
            title = RegionNames.short(region.title),
            subtitle = parents.ifEmpty { null },
            selected = selected?.placeId == null && selected?.region?.uid == region.uid,
            onClick = { onSelect(region) },
        )
    }
}

@Composable
private fun CurrentRegion(label: String) {
    Column(
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = stringResource(R.string.region_now),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(label, style = MaterialTheme.typography.titleSmall)
    }
}

/** Поле пошуку: 56, surfaceContainer, повністю округле. */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val placeholder = stringResource(R.string.region_search)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding, vertical = 8.dp)
            .heightIn(min = 56.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_search),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(modifier = Modifier.weight(1f).padding(vertical = 16.dp)) {
                if (query.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.action_cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp),
    )
}

/** «🗺 › Київська обл. › [Обухівський р-н]»: останній крок — чип, попередні — посилання. */
@Composable
private fun Breadcrumbs(
    oblast: Oblast,
    raionTitle: String?,
    onAllOblasts: () -> Unit,
    onOblast: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onAllOblasts) {
            Icon(
                painterResource(R.drawable.ic_map),
                contentDescription = stringResource(R.string.region_all_oblasts),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Chevron()
        val oblastName = RegionNames.short(oblast.title)
        if (raionTitle == null) {
            CurrentCrumb(oblastName)
        } else {
            Text(
                text = oblastName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                modifier = Modifier
                    .clickable(role = Role.Button, onClick = onOblast)
                    .padding(horizontal = 8.dp, vertical = 14.dp),
            )
            Chevron()
            CurrentCrumb(RegionNames.short(raionTitle), Modifier.weight(1f, fill = false))
        }
    }
}

@Composable
private fun Chevron() {
    Icon(
        painterResource(R.drawable.ic_chevron_right),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(18.dp),
    )
}

@Composable
private fun CurrentCrumb(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.padding(start = 4.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** Рядок з радіокнопкою; вибраний підсвічується primaryContainer (design-spec 3.3). */
@Composable
private fun RadioRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
    bold: Boolean = false,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
    ) {
        Row(
            modifier = Modifier.heightIn(min = 60.dp).padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 4.dp))
            Column(modifier = Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = if (bold) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun NavigateRow(title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 30.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun Loading() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator()
        Text(stringResource(R.string.region_loading), style = MaterialTheme.typography.bodyMedium)
    }
}
