package ua.vidbiy.app.alarm

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ua.vidbiy.app.R
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import ua.vidbiy.app.VidbiyApplication
import ua.vidbiy.app.data.Alarm
import ua.vidbiy.app.data.SettingsRepository
import ua.vidbiy.app.ui.AlarmRingActivity

/**
 * Дзвінок будильника: програє мелодію, вібрує й тримає повноекранну нотифікацію.
 *
 * Це foreground service — служба з постійною нотифікацією. Такій службі система не дає
 * померти, поки вона працює; звичайний фоновий код Android прибив би за секунди.
 */
class AlarmRingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var autoStopJob: Job? = null

    /** Будильники, що дзвонять зараз: дзвінок один, а екран і кнопки — на всіх разом. */
    private val ringing = LinkedHashMap<Long, RingEntry>()
    private var snoozeMinutes: Int = SettingsRepository.DEFAULT_SNOOZE_MINUTES

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRinging(intent)
            ACTION_SNOOZE -> {
                // Відкладення, як і вимкнення, стосується всіх будильників, що дзвонять.
                val scheduler = AlarmScheduler(this)
                val ids = ringing.keys.toList().ifEmpty { listOf(intent.getLongExtra(EXTRA_ALARM_ID, Alarm.NEW_ID)) }
                ids.forEach { scheduler.snooze(alarmId = it, minutes = snoozeMinutes) }
                stopEverything()
            }

            ACTION_DISMISS -> stopEverything()
            else -> stopEverything()
        }
        return START_NOT_STICKY
    }

    private fun startRinging(intent: Intent) {
        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, Alarm.NEW_ID)
        val hour = intent.getIntExtra(EXTRA_HOUR, 0)
        val minute = intent.getIntExtra(EXTRA_MINUTE, 0)
        val vibrate = intent.getBooleanExtra(EXTRA_VIBRATE, true)
        val ringtoneUri = intent.getStringExtra(EXTRA_RINGTONE_URI)
        val reasonJson = intent.getStringExtra(EXTRA_REASON)
        val reason = reasonJson?.let { runCatching { json.decodeFromString<RingReason>(it) }.getOrNull() } ?: RingReason.Plain
        snoozeMinutes = intent.getIntExtra(EXTRA_SNOOZE_MINUTES, SettingsRepository.DEFAULT_SNOOZE_MINUTES)

        // Другий будильник (той самий регіон закінчився одночасно, або наспів інший час) долучається
        // до дзвінка, що вже йде: звук один, а на екрані видно всі. Той самий будильник двічі
        // (скажімо, відкладення, що збіглося з дзвінком) просто оновлює свій запис.
        val alreadyRinging = ringing.isNotEmpty()
        ringing[alarmId] = RingEntry(alarmId, hour, minute, reason)
        RingState.set(ringing.values.toList())

        startForegroundNotification(alarmId, hour, minute, reasonJson)
        if (!alreadyRinging) {
            acquireWakeLock()
            startSound(ringtoneUri)
        }
        if (vibrate && vibrator == null) startVibration()

        // Будильник, який дзвонить годинами, розряджає телефон і дратує сусідів.
        // Через 10 хвилин замовкаємо — так само, як системний годинник.
        autoStopJob?.cancel()
        autoStopJob = scope.launch {
            delay(AUTO_STOP_MINUTES * 60_000L)
            stopEverything()
        }
    }

    private fun startForegroundNotification(alarmId: Long, hour: Int, minute: Int, reasonJson: String?) {
        // Один код запиту: повторний показ оновлює той самий повноекранний інтент, а не множить їх.
        val fullScreen = PendingIntent.getActivity(
            this,
            0,
            AlarmRingActivity.intent(this, alarmId, hour, minute, reasonJson, snoozeMinutes),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, Notifications.CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(getString(R.string.ring_title))
            .setContentText(
                if (ringing.size > 1) {
                    getString(R.string.ring_text_multiple, ringing.size)
                } else {
                    getString(R.string.ring_text, "%02d:%02d".format(hour, minute))
                },
            )
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .addAction(0, getString(R.string.ring_snooze, snoozeMinutes), servicePendingIntent(ACTION_SNOOZE, alarmId))
            .addAction(0, getString(R.string.ring_dismiss), servicePendingIntent(ACTION_DISMISS, alarmId))
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Notifications.ALARM_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(Notifications.ALARM_NOTIFICATION_ID, notification)
        }
    }

    private fun servicePendingIntent(action: String, alarmId: Long): PendingIntent {
        val intent = Intent(this, AlarmRingService::class.java).apply {
            this.action = action
            putExtra(EXTRA_ALARM_ID, alarmId)
        }
        return PendingIntent.getService(
            this,
            action.hashCode() + alarmId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun startSound(ringtoneUri: String?) {
        val attributes = AudioAttributes.Builder()
            // USAGE_ALARM — той самий потік, що й у системного будильника:
            // його чути в «Не турбувати» й керує ним гучність будильника, а не медіа.
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val uri = ringtoneUri?.let(Uri::parse)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

        player = play(uri, attributes) ?: fallbackPlayer(attributes)
    }

    private fun play(uri: Uri?, attributes: AudioAttributes): MediaPlayer? = runCatching {
        MediaPlayer().apply {
            setAudioAttributes(attributes)
            setDataSource(this@AlarmRingService, uri ?: error("Немає URI мелодії"))
            isLooping = true
            prepare()
            start()
        }
    }.onFailure { Log.w(TAG, "Не вдалося програти мелодію $uri", it) }.getOrNull()

    /**
     * Обрану мелодію могли видалити або вона могла виявитися недоступною.
     * Мовчазний будильник — найгірший зі сценаріїв, тож пробуємо типову, а потім рінгтон дзвінка.
     */
    private fun fallbackPlayer(attributes: AudioAttributes): MediaPlayer? =
        play(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), attributes)
            ?: play(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), attributes)

    private fun startVibration() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        } ?: return

        this.vibrator = vibrator
        val pattern = longArrayOf(0, 700, 800)
        vibrator.vibrate(
            VibrationEffect.createWaveform(pattern, 0),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
        )
    }

    private fun acquireWakeLock() {
        val power = getSystemService(PowerManager::class.java) ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire((AUTO_STOP_MINUTES + 1) * 60_000L)
        }
    }

    private fun stopEverything() {
        autoStopJob?.cancel()
        runCatching { player?.stop() }
        player?.release()
        player = null
        vibrator?.cancel()
        vibrator = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        ringing.clear()
        RingState.set(emptyList())
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopEverything()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AlarmRingService"
        private const val WAKE_LOCK_TAG = "vidbiy:ring"
        private const val AUTO_STOP_MINUTES = 10L

        const val ACTION_START = "ua.vidbiy.app.action.START_RINGING"
        const val ACTION_SNOOZE = "ua.vidbiy.app.action.SNOOZE"
        const val ACTION_DISMISS = "ua.vidbiy.app.action.DISMISS"

        const val EXTRA_ALARM_ID = "alarm_id"
        private const val EXTRA_HOUR = "hour"
        private const val EXTRA_MINUTE = "minute"
        private const val EXTRA_VIBRATE = "vibrate"
        private const val EXTRA_RINGTONE_URI = "ringtone_uri"
        private const val EXTRA_REASON = "reason"
        private const val EXTRA_SNOOZE_MINUTES = "snooze_minutes"
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Почати дзвінок. [reason] показується на екрані дзвінка (FR-21); тривалість
         * відкладення (FR-19) читаємо тут, щоб кнопки й сповіщення знали її одразу.
         */
        suspend fun startRinging(context: Context, alarm: Alarm, reason: RingReason = RingReason.Plain) {
            val app = context.applicationContext as VidbiyApplication
            val snooze = app.settingsRepository.snoozeMinutes.first()

            val intent = Intent(context, AlarmRingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ALARM_ID, alarm.id)
                putExtra(EXTRA_HOUR, alarm.hour)
                putExtra(EXTRA_MINUTE, alarm.minute)
                putExtra(EXTRA_VIBRATE, alarm.vibrate)
                putExtra(EXTRA_RINGTONE_URI, alarm.ringtoneUri)
                putExtra(EXTRA_REASON, json.encodeToString(reason))
                putExtra(EXTRA_SNOOZE_MINUTES, snooze)
            }
            context.startForegroundService(intent)
            // Дзвінок і очікування відбою одного будильника взаємно виключні. Очікування зупиняємо лише тепер,
            // коли дзвінок уже запущено: часто нас викликає саме служба очікування, і її зупинка
            // скасовує цю корутину. Зупинка до запуску обривала дзвінок на першому ж `first()`
            // вище — будильник мовчки не дзвонив після відбою (журнал рішень, 2026-09-29).
            // Після `startForegroundService` пауз немає, тож скасуванню тут нема чого обірвати.
            // Зупиняємо лише очікування цього будильника: решта (інші регіони) чекають далі.
            AlarmWaitService.stop(context, alarm.id)
        }

        fun snoozeIntent(context: Context, alarmId: Long): Intent =
            Intent(context, AlarmRingService::class.java).apply {
                action = ACTION_SNOOZE
                putExtra(EXTRA_ALARM_ID, alarmId)
            }

        fun dismissIntent(context: Context, alarmId: Long): Intent =
            Intent(context, AlarmRingService::class.java).apply {
                action = ACTION_DISMISS
                putExtra(EXTRA_ALARM_ID, alarmId)
            }
    }
}
