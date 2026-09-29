package ua.vidbiy.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ua.vidbiy.app.VidbiyApplication

/**
 * Журнал рішень будильника (лише debug-збірка): останні записи з `files/decisions.jsonl`,
 * кнопки «Копіювати» й «Очистити». Тексти не перекладені навмисно — це інструмент розробника,
 * а не частина дизайну.
 */
@Composable
fun DecisionLogCard(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as VidbiyApplication
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var lines by remember { mutableStateOf<List<String>?>(null) }

    SectionCard(title = "Журнал рішень (debug)", modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { scope.launch { lines = app.decisionLog.read() } }) {
                Text(if (lines == null) "Показати" else "Оновити")
            }
            TextButton(onClick = {
                scope.launch { clipboard.setText(AnnotatedString(app.decisionLog.read().joinToString("\n"))) }
            }) { Text("Копіювати все") }
            TextButton(onClick = { scope.launch { app.decisionLog.clear(); lines = emptyList() } }) {
                Text("Очистити")
            }
        }
        lines?.let { shown ->
            if (shown.isEmpty()) {
                FieldHint("Порожньо")
            } else {
                Text(
                    text = shown.take(SHOWN_LINES).joinToString("\n\n"),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private const val SHOWN_LINES = 60
