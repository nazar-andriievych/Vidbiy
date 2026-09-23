package ua.vidbiy.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
            onAdd = viewModel::startNew,
            onEdit = viewModel::startEdit,
            onToggle = viewModel::setEnabled,
            onPickRegion = viewModel::startPickRegion,
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
