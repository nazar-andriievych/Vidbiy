package ua.vidbiy.app.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ua.vidbiy.app.R
import ua.vidbiy.app.alarm.AlarmRingService
import ua.vidbiy.app.data.Alarm
import ua.vidbiy.app.ui.theme.VidbiyTheme

/**
 * Повноекранний дзвінок. Показується поверх екрана блокування й сам його вмикає —
 * саме для цього нотифікація служби несе «повноекранний інтент».
 *
 * Сам звук тут не програється: ним керує AlarmRingService, який переживе
 * закриття вікна (наприклад, якщо система вирішить прибрати активність).
 */
class AlarmRingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()

        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, Alarm.NEW_ID)
        val hour = intent.getIntExtra(EXTRA_HOUR, 0)
        val minute = intent.getIntExtra(EXTRA_MINUTE, 0)

        setContent {
            VidbiyTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RingContent(
                        time = formatTime(hour, minute),
                        onSnooze = {
                            startService(AlarmRingService.snoozeIntent(this, alarmId))
                            finish()
                        },
                        onDismiss = {
                            startService(AlarmRingService.dismissIntent(this, alarmId))
                            finish()
                        },
                    )
                }
            }
        }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        private const val EXTRA_ALARM_ID = "alarm_id"
        private const val EXTRA_HOUR = "hour"
        private const val EXTRA_MINUTE = "minute"

        fun intent(context: Context, alarmId: Long, hour: Int, minute: Int): Intent =
            Intent(context, AlarmRingActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(EXTRA_ALARM_ID, alarmId)
                putExtra(EXTRA_HOUR, hour)
                putExtra(EXTRA_MINUTE, minute)
            }
    }
}

@Composable
private fun RingContent(time: String, onSnooze: () -> Unit, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
    ) {
        Text(text = time, style = MaterialTheme.typography.displayLarge)
        Text(
            text = stringResource(R.string.ring_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.ring_dismiss))
        }
        OutlinedButton(onClick = onSnooze, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.ring_snooze))
        }
    }
}
