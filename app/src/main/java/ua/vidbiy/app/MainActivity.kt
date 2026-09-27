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
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
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
import ua.vidbiy.app.ui.SettingsTab
import ua.vidbiy.app.ui.WaitingScreen
import ua.vidbiy.app.ui.placeName
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
        if (intent?.action == ACTION_SHOW_WAITING) viewModel.openWaiting()
    }

    companion object {
        /** Відкрити екран очікування: зі сповіщення («Не дзвонити…» або натиск на нього). */
        const val ACTION_SHOW_WAITING = "ua.vidbiy.app.action.SHOW_WAITING"
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
    val places by viewModel.places.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val overlay by viewModel.overlay.collectAsStateWithLifecycle()
    val waiting by viewModel.pendingWait.collectAsStateWithLifecycle()
    val waitStatus by viewModel.waitStatus.collectAsStateWithLifecycle()
    val snoozeMinutes by viewModel.snoozeMinutes.collectAsStateWithLifecycle()
    val debugProxyUrl by viewModel.debugProxyUrl.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    // rememberSaveable переживає поворот екрана й повернення до застосунку, як стан у Bundle.
    var tab by rememberSaveable { mutableStateOf(Tab.Alarms) }

    RequestNotificationPermission()

    // Екранів небагато, тож навігаційна бібліотека надлишкова: повноекранні підекрани
    // (редагування, вибір регіону, нове місце) перекривають вкладки, поки відкриті.
    val editing = draft
    val current = overlay
    val waitingAlarm = waiting?.let { wait -> alarms.firstOrNull { it.id == wait.alarmId } }
    when {
        current == Overlay.Waiting -> {
            val wait = waiting
            if (wait != null && waitingAlarm != null) {
                WaitingScreen(
                    alarm = waitingAlarm,
                    placeName = placeName(waitingAlarm, places),
                    wait = wait,
                    status = waitStatus?.takeIf { it.alarmId == wait.alarmId },
                    snoozeMinutes = snoozeMinutes,
                    onBack = viewModel::closeOverlay,
                    onSnooze = { viewModel.snoozeWaiting(wait.alarmId) },
                    onSkip = { viewModel.cancelWaiting(wait.alarmId) },
                )
            } else {
                // Очікування закінчилося (задзвонив, скасували) — екрану більше нема що показувати.
                LaunchedEffect(Unit) { viewModel.closeOverlay() }
            }
        }
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
        )
        else -> Scaffold(
            bottomBar = { VidbiyNavigationBar(selected = tab, onSelect = { tab = it }) },
            floatingActionButton = {
                when (tab) {
                    Tab.Alarms -> VidbiyFab(R.string.action_new_alarm, viewModel::startNew)
                    Tab.Places -> VidbiyFab(R.string.places_add, viewModel::startAddPlace)
                    Tab.Settings -> Unit
                }
            },
        ) { padding ->
            // Запас унизу, щоб остання картка не ховалася під FAB.
            val content = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 96.dp,
            )
            when (tab) {
                Tab.Alarms -> AlarmsTab(
                    alarms = alarms,
                    places = places,
                    waiting = waiting,
                    waitStatus = waitStatus,
                    contentPadding = content,
                    onEdit = viewModel::startEdit,
                    onToggle = viewModel::setEnabled,
                    onOpenWaiting = viewModel::openWaiting,
                )
                Tab.Places -> PlacesTab(
                    places = places,
                    alarms = alarms,
                    contentPadding = content,
                    onMakePrimary = viewModel::makePrimary,
                    onRename = viewModel::renamePlace,
                    onChangeRegion = viewModel::startChangePlaceRegion,
                    onDelete = viewModel::deletePlace,
                )
                Tab.Settings -> SettingsTab(
                    snoozeMinutes = snoozeMinutes,
                    onSnoozeChange = viewModel::setSnoozeMinutes,
                    themeMode = themeMode,
                    debugProxyUrl = debugProxyUrl,
                    contentPadding = content,
                    onThemeModeChange = viewModel::setThemeMode,
                    onDebugProxyUrlChange = viewModel::setDebugProxyUrl,
                )
            }
        }
    }
}

/** Extended FAB: primaryContainer, радіус 16 (design-spec 2). */
@Composable
private fun VidbiyFab(@StringRes label: Int, onClick: () -> Unit) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
        text = { Text(stringResource(label), style = MaterialTheme.typography.labelLarge) },
    )
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
