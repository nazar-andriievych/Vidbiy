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
import ua.vidbiy.app.data.Alarm
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
    private var ringingAlarmId: Long = Alarm.NEW_ID

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRinging(intent)
            ACTION_SNOOZE -> {
                AlarmScheduler(this).snooze(
                    alarmId = intent.getLongExtra(EXTRA_ALARM_ID, ringingAlarmId),
                    minutes = SNOOZE_MINUTES,
                )
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
        ringingAlarmId = alarmId

        startForegroundNotification(alarmId, hour, minute)
        acquireWakeLock()
        startSound(ringtoneUri)
        if (vibrate) startVibration()

        // Будильник, який дзвонить годинами, розряджає телефон і дратує сусідів.
        // Через 10 хвилин замовкаємо — так само, як системний годинник.
        autoStopJob?.cancel()
        autoStopJob = scope.launch {
            delay(AUTO_STOP_MINUTES * 60_000L)
            stopEverything()
        }
    }

    private fun startForegroundNotification(alarmId: Long, hour: Int, minute: Int) {
        val fullScreen = PendingIntent.getActivity(
            this,
            alarmId.toInt(),
            AlarmRingActivity.intent(this, alarmId, hour, minute),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, Notifications.CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(getString(R.string.ring_title))
            .setContentText(getString(R.string.ring_text, "%02d:%02d".format(hour, minute)))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .addAction(0, getString(R.string.ring_snooze), servicePendingIntent(ACTION_SNOOZE, alarmId))
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

        /** FR-9: відкладення на 5 хвилин. */
        const val SNOOZE_MINUTES = 5

        const val ACTION_START = "ua.vidbiy.app.action.START_RINGING"
        const val ACTION_SNOOZE = "ua.vidbiy.app.action.SNOOZE"
        const val ACTION_DISMISS = "ua.vidbiy.app.action.DISMISS"

        const val EXTRA_ALARM_ID = "alarm_id"
        private const val EXTRA_HOUR = "hour"
        private const val EXTRA_MINUTE = "minute"
        private const val EXTRA_VIBRATE = "vibrate"
        private const val EXTRA_RINGTONE_URI = "ringtone_uri"

        fun startRinging(context: Context, alarm: Alarm) {
            // Дзвінок і очікування відбою взаємно виключні.
            AlarmWaitService.stop(context)

            val intent = Intent(context, AlarmRingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ALARM_ID, alarm.id)
                putExtra(EXTRA_HOUR, alarm.hour)
                putExtra(EXTRA_MINUTE, alarm.minute)
                putExtra(EXTRA_VIBRATE, alarm.vibrate)
                putExtra(EXTRA_RINGTONE_URI, alarm.ringtoneUri)
            }
            context.startForegroundService(intent)
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
