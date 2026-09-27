package ua.vidbiy.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import ua.vidbiy.app.ui.PlacesTab
import ua.vidbiy.app.ui.RegionPickerScreen
import ua.vidbiy.app.ui.SettingsTab
import ua.vidbiy.app.ui.theme.VidbiyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: AlarmsViewModel = viewModel(factory = AlarmsViewModel.Factory)
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            VidbiyTheme(mode = themeMode) {
                VidbiyApp(viewModel)
            }
        }
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
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val region by viewModel.region.collectAsStateWithLifecycle()
    val pickingRegion by viewModel.pickingRegion.collectAsStateWithLifecycle()
    val waiting by viewModel.pendingWait.collectAsStateWithLifecycle()
    val debugProxyUrl by viewModel.debugProxyUrl.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    // rememberSaveable переживає поворот екрана й повернення до застосунку, як стан у Bundle.
    var tab by rememberSaveable { mutableStateOf(Tab.Alarms) }

    RequestNotificationPermission()

    // Екранів небагато, тож навігаційна бібліотека надлишкова: повноекранні підекрани
    // (вибір регіону, редагування) перекривають вкладки, поки відкриті.
    val editing = draft
    when {
        pickingRegion -> RegionPickerScreen(
            onSelect = viewModel::selectRegion,
            onCancel = viewModel::cancelPickRegion,
        )
        editing != null -> AlarmEditScreen(
            alarm = editing,
            onChange = viewModel::updateDraft,
            onSave = { hour, minute ->
                viewModel.updateDraft { it.copy(hour = hour, minute = minute, enabled = true) }
                viewModel.saveDraft()
            },
            onDelete = viewModel::deleteDraft,
            onCancel = viewModel::cancelEdit,
        )
        else -> Scaffold(
            bottomBar = { VidbiyNavigationBar(selected = tab, onSelect = { tab = it }) },
            floatingActionButton = {
                if (tab == Tab.Alarms) {
                    ExtendedFloatingActionButton(
                        onClick = viewModel::startNew,
                        shape = MaterialTheme.shapes.small,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                        text = {
                            Text(stringResource(R.string.action_new_alarm), style = MaterialTheme.typography.labelLarge)
                        },
                    )
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
                    region = region,
                    waiting = waiting,
                    contentPadding = content,
                    onEdit = viewModel::startEdit,
                    onToggle = viewModel::setEnabled,
                    onCancelWaiting = viewModel::cancelWaiting,
                )
                Tab.Places -> PlacesTab(
                    region = region,
                    contentPadding = content,
                    onPickRegion = viewModel::startPickRegion,
                )
                Tab.Settings -> SettingsTab(
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
