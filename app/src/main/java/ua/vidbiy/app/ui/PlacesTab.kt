package ua.vidbiy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.vidbiy.app.R
import ua.vidbiy.app.data.Alarm
import ua.vidbiy.app.data.Place
import ua.vidbiy.app.data.PlacesState
import ua.vidbiy.app.data.SelectedRegion
import ua.vidbiy.app.data.label
import ua.vidbiy.app.data.shortTitle
import ua.vidbiy.app.ui.theme.Dimens
import ua.vidbiy.app.ui.theme.alertColors

/** Вкладка «Мої місця» (design-spec 3.4, `05-places`). */
@Composable
fun PlacesTab(
    places: PlacesState,
    alarms: List<Alarm>,
    contentPadding: PaddingValues,
    onMakePrimary: (Place) -> Unit,
    onRename: (Place, String) -> Unit,
    onChangeRegion: (Place) -> Unit,
    onDelete: (Place) -> Unit,
) {
    // Діалоги тримаємо за id: місце в списку може оновитися, поки діалог відкритий.
    var renamingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val primaryId = places.primary?.id

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(Dimens.ListGap),
    ) {
        item { TabHeader(stringResource(R.string.places_title)) }
        if (places.places.isEmpty()) {
            item { EmptyPlaces() }
            return@LazyColumn
        }
        item {
            FieldHint(
                text = stringResource(R.string.places_intro),
                modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 4.dp),
            )
        }
        items(places.ordered, key = { it.id }) { place ->
            PlaceCard(
                place = place,
                isPrimary = place.id == primaryId,
                onMakePrimary = { onMakePrimary(place) },
                onRename = { renamingId = place.id },
                onChangeRegion = { onChangeRegion(place) },
                onDelete = { deletingId = place.id },
            )
        }
    }

    places.byId(renamingId)?.let { place ->
        PlaceNameDialog(
            title = stringResource(R.string.place_rename_title),
            initialName = place.name,
            onSave = { name ->
                onRename(place, name)
                renamingId = null
            },
            onDismiss = { renamingId = null },
        )
    }

    places.byId(deletingId)?.let { place ->
        DeletePlaceDialog(
            place = place,
            isPrimary = place.id == primaryId,
            usedBy = alarms.filter { it.placeId == place.id },
            onConfirm = {
                onDelete(place)
                deletingId = null
            },
            onDismiss = { deletingId = null },
        )
    }
}

@Composable
private fun PlaceCard(
    place: Place,
    isPrimary: Boolean,
    onMakePrimary: () -> Unit,
    onRename: () -> Unit,
    onChangeRegion: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = place.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isPrimary) PrimaryChip()
                }
                FieldHint(place.region.label)
            }
            PlaceMenu(
                placeName = place.name,
                isPrimary = isPrimary,
                onMakePrimary = onMakePrimary,
                onRename = onRename,
                onChangeRegion = onChangeRegion,
                onDelete = onDelete,
            )
        }
    }
}

@Composable
private fun PrimaryChip() {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
        Text(
            text = stringResource(R.string.places_primary),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun PlaceMenu(
    placeName: String,
    isPrimary: Boolean,
    onMakePrimary: () -> Unit,
    onRename: () -> Unit,
    onChangeRegion: () -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                painterResource(R.drawable.ic_more_vert),
                contentDescription = stringResource(R.string.places_menu, placeName),
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = MaterialTheme.shapes.small,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            fun pick(action: () -> Unit) {
                open = false
                action()
            }
            if (!isPrimary) {
                MenuItem(stringResource(R.string.places_make_primary)) { pick(onMakePrimary) }
            }
            MenuItem(stringResource(R.string.places_rename)) { pick(onRename) }
            MenuItem(stringResource(R.string.places_change_region)) { pick(onChangeRegion) }
            MenuItem(stringResource(R.string.places_delete), destructive = true) { pick(onDelete) }
        }
    }
}

@Composable
private fun MenuItem(text: String, destructive: Boolean = false, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (destructive) MaterialTheme.alertColors.red else MaterialTheme.colorScheme.onSurface,
            )
        },
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
    )
}

/**
 * FR-26a: основне місце не видаляється; для інших діалог пояснює, що буде з будильниками —
 * вони зберігають регіон, лише без назви місця.
 */
@Composable
private fun DeletePlaceDialog(
    place: Place,
    isPrimary: Boolean,
    usedBy: List<Alarm>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (isPrimary) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.place_delete_primary_title)) },
            text = { Text(stringResource(R.string.place_delete_primary_text, place.name)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_understood)) } },
        )
        return
    }
    val times = usedBy.joinToString(", ") { formatTime(it.hour, it.minute) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.place_delete_title, place.name)) },
        text = {
            Text(
                when (usedBy.size) {
                    0 -> stringResource(R.string.place_delete_unused)
                    1 -> stringResource(R.string.place_delete_used_one, times, place.region.shortTitle, place.name)
                    else -> stringResource(R.string.place_delete_used_many, times, place.region.shortTitle, place.name)
                }
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.places_delete), color = MaterialTheme.alertColors.red)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Порожній стан (`02-empty--places-empty`). */
@Composable
private fun EmptyPlaces() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_location_on),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(36.dp),
            )
        }
        Text(
            text = stringResource(R.string.places_empty),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.places_empty_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Нове місце (design-spec 3.5, `06-add-place`): крок 1 — регіон, крок 2 — діалог з назвою
 * поверх того ж екрана. «Назад» у діалозі повертає до кроку 1 з тим самим вибором.
 */
@Composable
fun AddPlaceScreen(onSave: (String, SelectedRegion) -> Unit, onBack: () -> Unit) {
    var picked by remember { mutableStateOf<RegionPick?>(null) }
    var naming by rememberSaveable { mutableStateOf(false) }

    RegionPickerScreen(
        title = stringResource(R.string.place_new_title),
        confirmLabel = stringResource(R.string.action_next),
        stepLabel = stringResource(R.string.place_step1),
        initial = picked,
        onConfirm = {
            picked = it
            naming = true
        },
        onBack = onBack,
    )

    val region = picked?.region
    if (naming && region != null) {
        PlaceNameDialog(
            title = stringResource(R.string.place_name_title),
            initialName = "",
            stepLabel = stringResource(R.string.place_step2),
            regionLabel = region.label,
            dismissLabel = stringResource(R.string.action_back),
            onSave = { name -> onSave(name, region) },
            onDismiss = { naming = false },
        )
    }
}

/**
 * «Змінити регіон» місця (`05-places--change-region`). FR-26a: будильники з цим місцем
 * переходять на новий регіон — екран попереджає, які саме.
 */
@Composable
fun PlaceRegionScreen(
    place: Place,
    usedBy: List<Alarm>,
    onSave: (SelectedRegion) -> Unit,
    onBack: () -> Unit,
) {
    val times = usedBy.joinToString(", ") { formatTime(it.hour, it.minute) }
    RegionPickerScreen(
        title = stringResource(R.string.region_for_place, place.name),
        confirmLabel = stringResource(R.string.action_save),
        currentLabel = place.region.label,
        initial = RegionPick(place.region),
        note = when (usedBy.size) {
            0 -> null
            1 -> stringResource(R.string.place_region_used_one, times)
            else -> stringResource(R.string.place_region_used_many, times)
        },
        onConfirm = { pick -> if (pick.region == place.region) onBack() else onSave(pick.region) },
        onBack = onBack,
    )
}
