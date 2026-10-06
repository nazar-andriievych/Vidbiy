package ua.vidbiy.app.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.annotation.StringRes
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import ua.vidbiy.app.alarm.RingEntry
import ua.vidbiy.app.alarm.RingReason
import ua.vidbiy.app.alarm.RingState
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
        // Запасний варіант, якщо служба ще не встигла заповнити RingState.
        val fromIntent = RingEntry(alarmId, hour, minute, reason)
        val settings = (application as VidbiyApplication).settingsRepository

        setContent {
            val themeMode by settings.themeMode.collectAsState(initial = ThemeMode.System)
            // Усі будильники, що дзвонять зараз: нові долучаються, поки екран відкритий.
            val ringing by RingState.entries.collectAsState()
            VidbiyTheme(mode = themeMode) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    RingScreen(
                        entries = ringing.ifEmpty { listOf(fromIntent) },
                        snoozeMinutes = snoozeMinutes,
                        // Служба відкладає й вимикає всіх разом; id потрібен лише як запасний.
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
 * Якщо дзвонить кілька будильників, замість одного блоку причини — список: у кожного своя
 * причина, а кнопки діють на всіх.
 */
@Composable
private fun RingScreen(
    entries: List<RingEntry>,
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
            text = if (entries.size > 1) stringResource(R.string.ring_label_multiple, entries.size) else entryLabel(entries.first()),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(formatClock(now), style = MaterialTheme.typography.displayLarge, maxLines = 1)
        FieldHint(todayLabel())

        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (entries.size > 1) {
                RingList(entries)
            } else {
                val reason = entries.first().reason
                if (reason.kind != RingReason.Kind.PLAIN) ReasonBlock(reason)
            }
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
        }
    }
}

/** «Будильник 06:45 · Дім» / «Розбуди після відбою · Дім». */
@Composable
private fun entryLabel(entry: RingEntry): String {
    val reason = entry.reason
    return when {
        reason.oneShot -> stringResource(R.string.ring_label_one_shot, reason.placeName.orEmpty())
        reason.placeName != null -> stringResource(R.string.ring_label, formatTime(entry.hour, entry.minute), reason.placeName)
        else -> stringResource(R.string.ring_label_no_place, formatTime(entry.hour, entry.minute))
    }
}

/** Кілька будильників: кожен — карткою зі своїм підписом і причиною. */
@Composable
private fun RingList(entries: List<RingEntry>) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        entries.forEach { entry ->
            val reason = entry.reason
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = entryLabel(entry),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    reasonTitle(reason)?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
    }
}

/** Заголовок причини для списку; у звичайного будильника його немає. */
@Composable
private fun reasonTitle(reason: RingReason): String? = reasonTitleRes(reason)?.let { stringResource(it) }

/**
 * Заголовок причини (FR-21): «Тривоги немає», «Відбій тривоги»… Звичайний будильник — без блоку причини.
 * FR-24: у разовому режимі застарілі дані — «Немає даних», а не «Немає зв'язку».
 */
@StringRes
fun reasonTitleRes(reason: RingReason): Int? = when (reason.kind) {
    RingReason.Kind.ALL_CLEAR -> R.string.ring_all_clear_title
    RingReason.Kind.NO_ALERT -> R.string.ring_no_alert_title
    RingReason.Kind.DEADLINE -> R.string.ring_deadline_title
    RingReason.Kind.NO_CONNECTION -> R.string.ring_no_connection_title
    RingReason.Kind.STALE -> if (reason.oneShot) R.string.ring_no_data_title else R.string.ring_no_connection_title
    RingReason.Kind.TOO_LONG -> R.string.ring_too_long_title
    RingReason.Kind.APP_FAILURE -> R.string.ring_app_failure_title
    RingReason.Kind.LOCKED_BOOT -> R.string.ring_locked_boot_title
    RingReason.Kind.PLAIN -> null
}

@Composable
private fun ReasonBlock(reason: RingReason) {
    // Пояснення — лише якщо воно щось додає до заголовка й чипа (design-spec 3.10).
    // Місце вже є над часом («Будильник 06:45 · Дім»), тож тут його не повторюємо.
    val (icon, title, detail) = when (reason.kind) {
        RingReason.Kind.ALL_CLEAR -> Triple(
            R.drawable.ic_check,
            stringResource(R.string.ring_all_clear_title),
            reason.allClearAtMillis?.let { stringResource(R.string.ring_all_clear_text, formatClock(it)) },
        )
        RingReason.Kind.NO_ALERT -> Triple(
            R.drawable.ic_notifications,
            stringResource(R.string.ring_no_alert_title),
            null,
        )
        RingReason.Kind.DEADLINE -> Triple(
            R.drawable.ic_schedule,
            stringResource(R.string.ring_deadline_title),
            null,
        )
        RingReason.Kind.NO_CONNECTION -> Triple(
            R.drawable.ic_cloud_off,
            stringResource(R.string.ring_no_connection_title),
            stringResource(R.string.ring_no_connection_text),
        )
        RingReason.Kind.STALE -> Triple(
            R.drawable.ic_cloud_off,
            // FR-24: у разовому режимі — «Немає даних», а не «Немає зв'язку».
            stringResource(reasonTitleRes(reason)!!),
            stringResource(R.string.ring_stale_text),
        )
        RingReason.Kind.TOO_LONG -> Triple(
            R.drawable.ic_schedule,
            stringResource(R.string.ring_too_long_title),
            stringResource(R.string.ring_too_long_text),
        )
        RingReason.Kind.APP_FAILURE -> Triple(
            R.drawable.ic_info,
            stringResource(R.string.ring_app_failure_title),
            stringResource(R.string.ring_app_failure_text),
        )
        RingReason.Kind.LOCKED_BOOT -> Triple(
            R.drawable.ic_info,
            stringResource(R.string.ring_locked_boot_title),
            stringResource(R.string.ring_locked_boot_text),
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
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
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
