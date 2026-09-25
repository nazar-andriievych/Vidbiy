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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ua.vidbiy.app.ui.AlarmEditScreen
import ua.vidbiy.app.ui.AlarmListScreen
import ua.vidbiy.app.ui.AlarmsViewModel
import ua.vidbiy.app.ui.RegionPickerScreen
import ua.vidbiy.app.ui.theme.VidbiyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VidbiyTheme {
                VidbiyApp()
            }
        }
    }
}

@Composable
fun VidbiyApp(viewModel: AlarmsViewModel = viewModel(factory = AlarmsViewModel.Factory)) {
    val alarms by viewModel.alarms.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val region by viewModel.region.collectAsStateWithLifecycle()
    val pickingRegion by viewModel.pickingRegion.collectAsStateWithLifecycle()
    val waiting by viewModel.pendingWait.collectAsStateWithLifecycle()
    val debugProxyUrl by viewModel.debugProxyUrl.collectAsStateWithLifecycle()

    RequestNotificationPermission()

    // Екранів поки три, тож навігаційна бібліотека надлишкова:
    // є чернетка — редагуємо, відкритий вибір регіону — показуємо довідник, інакше список.
    val editing = draft
    if (pickingRegion) {
        RegionPickerScreen(
            onSelect = viewModel::selectRegion,
            onCancel = viewModel::cancelPickRegion,
        )
    } else if (editing == null) {
        AlarmListScreen(
            alarms = alarms,
            region = region,
            waiting = waiting,
            debugProxyUrl = debugProxyUrl,
            onAdd = viewModel::startNew,
            onEdit = viewModel::startEdit,
            onToggle = viewModel::setEnabled,
            onCancelWaiting = viewModel::cancelWaiting,
            onPickRegion = viewModel::startPickRegion,
            onDebugProxyUrlChange = viewModel::setDebugProxyUrl,
        )
    } else {
        AlarmEditScreen(
            alarm = editing,
            onChange = viewModel::updateDraft,
            onSave = { hour, minute ->
                viewModel.updateDraft { it.copy(hour = hour, minute = minute, enabled = true) }
                viewModel.saveDraft()
            },
            onDelete = viewModel::deleteDraft,
            onCancel = viewModel::cancelEdit,
        )
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
