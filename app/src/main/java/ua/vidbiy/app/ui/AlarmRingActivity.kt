package ua.vidbiy.app.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import ua.vidbiy.app.R
import ua.vidbiy.app.VidbiyApplication
import ua.vidbiy.app.alarm.AlarmRingService
import ua.vidbiy.app.alarm.RingReason
import ua.vidbiy.app.data.Alarm
import ua.vidbiy.app.data.AlertLevel
import ua.vidbiy.app.data.SettingsRepository
import ua.vidbiy.app.ui.theme.Dimens
import ua.vidbiy.app.ui.theme.ThemeMode
import ua.vidbiy.app.ui.theme.VidbiyTheme
import ua.vidbiy.app.ui.theme.alertColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Повноекранний дзвінок (design-spec 3.10). Показується поверх екрана блокування й сам
 * його вмикає — саме для цього сповіщення служби несе «повноекранний інтент».
 *
 * Сам звук тут не програється: ним керує AlarmRingService, який переживе
 * закриття вікна (наприклад, якщо система вирішить прибрати активність).
 */
class AlarmRingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        showOverLockScreen()

        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, Alarm.NEW_ID)
        val hour = intent.getIntExtra(EXTRA_HOUR, 0)
        val minute = intent.getIntExtra(EXTRA_MINUTE, 0)
        val snoozeMinutes = intent.getIntExtra(EXTRA_SNOOZE_MINUTES, SettingsRepository.DEFAULT_SNOOZE_MINUTES)
        val reason = intent.getStringExtra(EXTRA_REASON)
            ?.let { runCatching { json.decodeFromString<RingReason>(it) }.getOrNull() }
            ?: RingReason.Plain
        val settings = (application as VidbiyApplication).settingsRepository

        setContent {
            val themeMode by settings.themeMode.collectAsState(initial = ThemeMode.System)
            VidbiyTheme(mode = themeMode) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    RingScreen(
                        time = formatTime(hour, minute),
                        reason = reason,
                        snoozeMinutes = snoozeMinutes,
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
        private const val EXTRA_REASON = "reason"
        private const val EXTRA_SNOOZE_MINUTES = "snooze_minutes"
        private val json = Json { ignoreUnknownKeys = true }

        fun intent(
            context: Context,
            alarmId: Long,
            hour: Int,
            minute: Int,
            reasonJson: String?,
            snoozeMinutes: Int,
        ): Intent = Intent(context, AlarmRingActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra(EXTRA_HOUR, hour)
            putExtra(EXTRA_MINUTE, minute)
            putExtra(EXTRA_REASON, reasonJson)
            putExtra(EXTRA_SNOOZE_MINUTES, snoozeMinutes)
        }
    }
}

/**
 * «Будильник 06:45 · Дім», поточний час великим, дата; блок причини (крім звичайного
 * будильника); внизу «Відкласти на X хв» і «Вимкнути» з утриманням (FR-21).
 */
@Composable
private fun RingScreen(
    time: String,
    reason: RingReason,
    snoozeMinutes: Int,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit,
) {
    val now = rememberNowMillis(periodMillis = 1_000L)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = Dimens.ScreenPadding, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.heightIn(min = 48.dp))
        Text(
            text = when {
                reason.oneShot -> stringResource(R.string.ring_label_one_shot, reason.placeName.orEmpty())
                reason.placeName != null -> stringResource(R.string.ring_label, time, reason.placeName)
                else -> stringResource(R.string.ring_label_no_place, time)
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(formatClock(now), style = MaterialTheme.typography.displayLarge, maxLines = 1)
        FieldHint(todayLabel())

        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (reason.kind != RingReason.Kind.PLAIN) ReasonBlock(reason)
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledTonalButton(
                onClick = onSnooze,
                modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.SnoozeButtonHeight),
            ) {
                Text(stringResource(R.string.ring_snooze, snoozeMinutes), style = MaterialTheme.typography.labelLarge)
            }
            HoldButton(
                text = stringResource(R.string.ring_dismiss),
                hint = stringResource(R.string.ring_dismiss_hint),
                icon = R.drawable.ic_power_settings_new,
                accent = true,
                onConfirmed = onDismiss,
            )
            FieldHint(stringResource(R.string.ring_dismiss_hint))
        }
    }
}

@Composable
private fun ReasonBlock(reason: RingReason) {
    val place = reason.placeName ?: stringResource(R.string.ring_region_fallback)
    val (icon, title, detail) = when (reason.kind) {
        RingReason.Kind.ALL_CLEAR -> {
            val at = formatClock(reason.allClearAtMillis ?: 0L)
            Triple(
                R.drawable.ic_check,
                stringResource(R.string.ring_all_clear_title),
                if (reason.pauseMinutes > 0) {
                    stringResource(R.string.ring_all_clear_text, place, at, reason.pauseMinutes)
                } else {
                    stringResource(R.string.ring_all_clear_text_no_pause, place, at)
                },
            )
        }
        RingReason.Kind.NO_ALERT -> Triple(
            R.drawable.ic_notifications,
            stringResource(R.string.ring_no_alert_title),
            stringResource(R.string.ring_no_alert_text, place),
        )
        RingReason.Kind.DEADLINE -> Triple(
            R.drawable.ic_schedule,
            stringResource(R.string.ring_deadline_title),
            stringResource(R.string.ring_deadline_text, reason.deadlineMillis?.let(::formatClock).orEmpty()),
        )
        RingReason.Kind.NO_CONNECTION -> Triple(
            R.drawable.ic_cloud_off,
            stringResource(R.string.ring_no_connection_title),
            stringResource(R.string.ring_no_connection_text),
        )
        RingReason.Kind.STALE -> Triple(
            R.drawable.ic_cloud_off,
            // FR-24: у разовому режимі — «Немає даних», а не «Немає зв'язку».
            stringResource(if (reason.oneShot) R.string.ring_no_data_title else R.string.ring_no_connection_title),
            stringResource(R.string.ring_stale_text),
        )
        RingReason.Kind.TOO_LONG -> Triple(
            R.drawable.ic_schedule,
            stringResource(R.string.ring_too_long_title),
            stringResource(R.string.ring_too_long_text, place),
        )
        RingReason.Kind.PLAIN -> return
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        Box(
            modifier = Modifier.size(64.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        val level = reason.level
        if (level != null && (reason.kind == RingReason.Kind.DEADLINE || reason.kind == RingReason.Kind.TOO_LONG)) {
            OngoingLevelChip(level)
        }
    }
}

/** «● Червона тривога триває» — для крайнього часу й тривоги понад добу. */
@Composable
private fun OngoingLevelChip(level: AlertLevel) {
    val colors = MaterialTheme.alertColors
    val red = level == AlertLevel.RED
    Surface(shape = CircleShape, color = if (red) colors.redContainer else colors.yellowContainer) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(10.dp).background(if (red) colors.red else colors.yellow, CircleShape))
            Text(
                text = stringResource(if (red) R.string.level_red_ongoing else R.string.level_yellow_ongoing),
                style = MaterialTheme.typography.labelSmall,
                color = if (red) colors.onRedContainer else colors.onYellowContainer,
            )
        }
    }
}

/** «Неділя, 27 вересня». */
private fun todayLabel(): String =
    LocalDate.now()
        .format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.forLanguageTag("uk")))
        .replaceFirstChar { it.titlecase(Locale.forLanguageTag("uk")) }
