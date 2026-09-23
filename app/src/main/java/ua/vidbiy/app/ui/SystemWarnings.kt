package ua.vidbiy.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import ua.vidbiy.app.R
import ua.vidbiy.app.alarm.AlarmScheduler

/**
 * Три системні налаштування, без яких будильник може не задзвонити (NFR-2).
 * Перевіряємо їх щоразу, коли застосунок повертається на екран: користувач міг
 * змінити їх у системних налаштуваннях, нічого нам не сказавши.
 */
private data class SystemReadiness(
    val notificationsEnabled: Boolean = true,
    val exactAlarmsAllowed: Boolean = true,
    val batteryUnrestricted: Boolean = true,
) {
    val allGood: Boolean get() = notificationsEnabled && exactAlarmsAllowed && batteryUnrestricted
}

private fun readReadiness(context: Context) = SystemReadiness(
    notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
    exactAlarmsAllowed = AlarmScheduler(context).canScheduleExact(),
    batteryUnrestricted = context.getSystemService(PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(context.packageName) ?: true,
)

@Composable
fun SystemWarnings(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var readiness by remember { mutableStateOf(SystemReadiness()) }

    LifecycleResumeEffect(Unit) {
        readiness = readReadiness(context)
        onPauseOrDispose { }
    }

    if (readiness.allGood) return

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!readiness.notificationsEnabled) {
            WarningCard(
                title = stringResource(R.string.warning_notifications_title),
                text = stringResource(R.string.warning_notifications_text),
                onFix = { context.startActivity(notificationSettings(context)) },
            )
        }
        if (!readiness.exactAlarmsAllowed) {
            WarningCard(
                title = stringResource(R.string.warning_exact_title),
                text = stringResource(R.string.warning_exact_text),
                onFix = { exactAlarmSettings(context)?.let(context::startActivity) },
            )
        }
        if (!readiness.batteryUnrestricted) {
            WarningCard(
                title = stringResource(R.string.warning_battery_title),
                text = stringResource(R.string.warning_battery_text),
                onFix = {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                },
            )
        }
    }
}

@Composable
private fun WarningCard(title: String, text: String, onFix: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onFix) { Text(stringResource(R.string.warning_action)) }
        }
    }
}

private fun notificationSettings(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

private fun exactAlarmSettings(context: Context): Intent? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Intent(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Uri.fromParts("package", context.packageName, null),
        )
    } else {
        null
    }
