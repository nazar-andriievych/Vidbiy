package ua.vidbiy.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import ua.vidbiy.app.ui.nextRingLabel
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ua.vidbiy.app.ui.AlarmEditScreen
import ua.vidbiy.app.ui.AlarmsTab
import ua.vidbiy.app.ui.AlarmsViewModel
import ua.vidbiy.app.ui.AddPlaceScreen
import ua.vidbiy.app.ui.Overlay
import ua.vidbiy.app.ui.PlaceRegionScreen
import ua.vidbiy.app.ui.RegionPick
import ua.vidbiy.app.ui.PlacesTab
import ua.vidbiy.app.ui.RegionPickerScreen
import ua.vidbiy.app.ui.PermissionsScreen
import ua.vidbiy.app.ui.SettingsTab
import ua.vidbiy.app.ui.rememberMissingPermissions
import ua.vidbiy.app.ui.theme.VidbiyTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AlarmsViewModel by viewModels { AlarmsViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Лише на першому створенні: після повороту екрана Intent той самий, а стан уже відновлено.
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            VidbiyTheme(mode = themeMode) {
                VidbiyApp(viewModel)
            }
        }
    }

    // Активність уже відкрита, а користувач натиснув сповіщення очікування.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != ACTION_SHOW_ALARMS) return
        viewModel.requestShowAlarms()
    }

    companion object {
        /** Показати список будильників зі сповіщення очікування («Не дзвонити…» або натиск на нього). */
        const val ACTION_SHOW_ALARMS = "ua.vidbiy.app.action.SHOW_ALARMS"
    }
}

/** Вкладки нижньої навігації (design-spec 2). */
private enum class Tab(@StringRes val label: Int, @DrawableRes val icon: Int) {
    Alarms(R.string.nav_alarms, R.drawable.ic_alarm),
    Places(R.string.nav_places, R.drawable.ic_location_on),
    Settings(R.string.nav_settings, R.drawable.ic_tune),
}

@Composable
fun VidbiyApp(viewModel: AlarmsViewModel) {
    val alarms by viewModel.alarms.collectAsStateWithLifecycle()
    val alarmsUnreadable by viewModel.alarmsUnreadable.collectAsStateWithLifecycle()
    val places by viewModel.places.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val overlay by viewModel.overlay.collectAsStateWithLifecycle()
    val waits by viewModel.pendingWaits.collectAsStateWithLifecycle()
    val waitStatuses by viewModel.waitStatuses.collectAsStateWithLifecycle()
    val snoozes by viewModel.pendingSnoozes.collectAsStateWithLifecycle()
    val snoozeMinutes by viewModel.snoozeMinutes.collectAsStateWithLifecycle()
    val oneShotRow by viewModel.oneShotRow.collectAsStateWithLifecycle()
    val oneShotWaitFor by viewModel.oneShotWaitFor.collectAsStateWithLifecycle()
    val oneShotPause by viewModel.oneShotPauseMinutes.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    // rememberSaveable переживає поворот екрана й повернення до застосунку, як стан у Bundle.
    var tab by rememberSaveable { mutableStateOf(Tab.Alarms) }
    // Тут, а не у вкладці: щоб сповіщення могло прокрутити список до картки, що чекає.
    val alarmsListState = rememberLazyListState()

    RequestNotificationPermission()

    // Після збереження: «Спрацює завтра о 06:45» — щоб одразу було видно, чи той день.
    val snackbar = remember { SnackbarHostState() }
    // Власний scope: після consume цей блок зникає з композиції разом із LaunchedEffect,
    // а повідомлення має лишитися на екрані свої кілька секунд.
    val snackbarScope = rememberCoroutineScope()
    viewModel.savedNextRing.collectAsStateWithLifecycle().value?.let { next ->
        val message = stringResource(R.string.saved_next_ring, nextRingLabel(next))
        LaunchedEffect(next) {
            viewModel.consumeSavedNextRing()
            snackbarScope.launch { snackbar.showSnackbar(message) }
        }
    }
    if (viewModel.oneShotEnabled.collectAsStateWithLifecycle().value) {
        val message = stringResource(R.string.one_shot_enabled)
        LaunchedEffect(Unit) {
            viewModel.consumeOneShotEnabled()
            snackbarScope.launch { snackbar.showSnackbar(message) }
        }
    }
    // Сповіщення очікування: картка, що чекає, і банер режиму — нагорі вкладки «Будильники».
    if (viewModel.showAlarms.collectAsStateWithLifecycle().value) {
        LaunchedEffect(Unit) {
            viewModel.consumeShowAlarms()
            tab = Tab.Alarms
            alarmsListState.scrollToItem(0)
        }
    }

    // Екранів небагато, тож навігаційна бібліотека надлишкова: повноекранні підекрани
    // (редагування, вибір регіону, нове місце) перекривають вкладки, поки відкриті.
    val editing = draft
    val current = overlay
    // Дозволи перевіряються тут, щоб банер на головному й екран дозволів бачили один стан.
    val missingPermissions = rememberMissingPermissions()
    when {
        current == Overlay.Permissions -> PermissionsScreen(onBack = viewModel::closeOverlay)
        current == Overlay.AlarmRegion && editing != null -> RegionPickerScreen(
            title = stringResource(R.string.region_title),
            confirmLabel = stringResource(R.string.action_done),
            initial = editing.region?.let { RegionPick(it, editing.placeId) },
            places = places.ordered,
            primaryPlaceId = places.primary?.id,
            onConfirm = viewModel::setDraftRegion,
            onBack = viewModel::closeOverlay,
        )
        current == Overlay.AddPlace -> AddPlaceScreen(
            onSave = viewModel::addPlace,
            onBack = viewModel::closeOverlay,
        )
        current is Overlay.PlaceRegion -> {
            val place = places.byId(current.placeId)
            if (place != null) {
                PlaceRegionScreen(
                    place = place,
                    usedBy = alarms.filter { it.placeId == place.id },
                    onSave = { region -> viewModel.changePlaceRegion(place.id, region) },
                    onBack = viewModel::closeOverlay,
                )
            } else {
                // Місце зникло, поки екран був відкритий.
                LaunchedEffect(current) { viewModel.closeOverlay() }
            }
        }
        editing != null -> AlarmEditScreen(
            alarm = editing,
            placeName = places.byId(editing.placeId)?.name,
            onChange = viewModel::updateDraft,
            onPickRegion = viewModel::startPickAlarmRegion,
            onSave = viewModel::saveDraft,
            onDelete = viewModel::deleteDraft,
            onCancel = viewModel::cancelEdit,
            stopsWaiting = viewModel::draftStopsWaiting,
        )
        else -> Scaffold(
            bottomBar = { VidbiyNavigationBar(selected = tab, onSelect = { tab = it }) },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            // Згори — не відступ списку, а обрізання: інакше прокручений вміст заїжджав би під статус-бар.
            val content = PaddingValues(bottom = padding.calculateBottomPadding() + 16.dp)
            Box(Modifier.padding(top = padding.calculateTopPadding()).clipToBounds()) {
                when (tab) {
                    Tab.Alarms -> AlarmsTab(
                        alarms = alarms,
                        places = places,
                        waits = waits,
                        waitStatuses = waitStatuses,
                        snoozes = snoozes,
                        onCancelSnooze = viewModel::cancelSnooze,
                        contentPadding = content,
                        onAdd = viewModel::startNew,
                        onEdit = viewModel::startEdit,
                        onToggle = viewModel::setEnabled,
                        snoozeMinutes = snoozeMinutes,
                        onSnoozeWaiting = viewModel::snoozeWaiting,
                        onSkipWaiting = viewModel::cancelWaiting,
                        oneShotRow = oneShotRow,
                        onStartOneShot = viewModel::startOneShot,
                        onCancelOneShotCheck = viewModel::cancelOneShotCheck,
                        onNeedPlace = { tab = Tab.Places },
                        missingPermissions = missingPermissions,
                        onOpenPermissions = viewModel::openPermissions,
                        alarmsUnreadable = alarmsUnreadable,
                        listState = alarmsListState,
                    )
                    Tab.Places -> PlacesTab(
                        places = places,
                        alarms = alarms,
                        onAdd = viewModel::startAddPlace,
                        contentPadding = content,
                        onMakePrimary = viewModel::makePrimary,
                        onRename = viewModel::renamePlace,
                        onChangeRegion = viewModel::startChangePlaceRegion,
                        onDelete = viewModel::deletePlace,
                    )
                    Tab.Settings -> SettingsTab(
                        snoozeMinutes = snoozeMinutes,
                        onSnoozeChange = viewModel::setSnoozeMinutes,
                        primaryPlace = places.primary,
                        oneShotWaitFor = oneShotWaitFor,
                        oneShotPauseMinutes = oneShotPause,
                        onOneShotWaitFor = viewModel::setOneShotWaitFor,
                        onOneShotPause = viewModel::setOneShotPauseMinutes,
                        onOpenPlaces = { tab = Tab.Places },
                        onOpenPermissions = viewModel::openPermissions,
                        themeMode = themeMode,
                        contentPadding = content,
                        onThemeModeChange = viewModel::setThemeMode,
                    )
                }
            }
        }
    }
}

/** NavigationBar: висота 80, фон surfaceContainer, активний підпис 700, неактивні 500. */
@Composable
private fun VidbiyNavigationBar(selected: Tab, onSelect: (Tab) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Tab.entries.forEach { tab ->
            val isSelected = tab == selected
            NavigationBarItem(
                selected = isSelected,
                onClick = { onSelect(tab) },
                icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                label = {
                    Text(
                        text = stringResource(tab.label),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

/**
 * Починаючи з Android 13 сповіщення треба питати окремо. Без дозволу нотифікація
 * дзвінка не з'явиться — а разом із нею й повноекранний екран будильника.
 */
@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
