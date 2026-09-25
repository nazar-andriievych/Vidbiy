package ua.vidbiy.app.alarm

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ua.vidbiy.app.R
import ua.vidbiy.app.VidbiyApplication
import ua.vidbiy.app.data.Alarm
import ua.vidbiy.app.data.AlertsClient
import ua.vidbiy.app.data.AlertsSnapshot
import ua.vidbiy.app.data.PendingWait
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Очікування відбою (FR-4, FR-5, FR-8).
 *
 * Поки в обраному регіоні триває тривога, будильник мовчить, а тут висить тиха нотифікація
 * зі станом. Кожні 30 секунд опитуємо проксі; щойно тривоги не стало — дзвонимо.
 *
 * Служба не єдиний запобіжник: крайній час окремо зареєстрований у AlarmManager
 * (AlarmScheduler.scheduleDeadline), тож навіть якщо систему занесе й вона прибере цей
 * процес, будильник однаково задзвонить.
 */
class AlarmWaitService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var pollJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val alarmId = intent?.getLongExtra(EXTRA_ALARM_ID, Alarm.NEW_ID) ?: Alarm.NEW_ID

        when (intent?.action) {
            ACTION_START -> startWaiting(alarmId, intent.getLongExtra(EXTRA_DEADLINE_MILLIS, 0L))
            ACTION_RING_NOW -> ringNow(alarmId)
            ACTION_CANCEL -> cancelAlarm(alarmId)
            else -> stopEverything()
        }
        // START_REDELIVER_INTENT: якщо систему змусять прибити службу, вона віддасть їй
        // той самий Intent наново — разом із будильником і крайнім часом.
        return START_REDELIVER_INTENT
    }

    private fun startWaiting(alarmId: Long, deadlineMillis: Long) {
        if (alarmId == Alarm.NEW_ID || deadlineMillis == 0L) {
            stopEverything()
            return
        }

        startForegroundNotification(alarmId, deadlineMillis, status = null)
        acquireWakeLock(deadlineMillis)
        app().applicationScope.launch {
            app().settingsRepository.setPendingWait(PendingWait(alarmId, deadlineMillis))
        }

        pollJob?.cancel()
        pollJob = scope.launch { pollUntilClear(alarmId, deadlineMillis) }
    }

    private suspend fun pollUntilClear(alarmId: Long, deadlineMillis: Long) {
        val app = app()
        val baseUrl = app.settingsRepository.proxyBaseUrl()
        val client = AlertsClient(baseUrl)
        Log.i(TAG, "Чекаємо відбою: будильник=$alarmId, проксі=$baseUrl")

        while (currentCoroutineContext().isActive) {
            val alarm = app.alarmsRepository.alarms.first().firstOrNull { it.id == alarmId }
            if (alarm == null) {
                // Будильник видалили, поки ми чекали — чекати більше нема для кого.
                AlarmScheduler(this).cancelDeadline(alarmId)
                stopEverything()
                return
            }

            val region = app.settingsRepository.selectedRegion.first()
            val snapshot = client.fetch()
            val nowElapsed = SystemClock.elapsedRealtime()
            val decision = decideRing(
                snapshot = snapshot,
                nowElapsed = nowElapsed,
                region = region,
                pastDeadline = System.currentTimeMillis() >= deadlineMillis,
            )
            Log.i(
                TAG,
                "Рішення=$decision, регіон=${region?.uid} (${region?.title}), " +
                    "тривоги=${snapshot.alertUids}, вік=${snapshot.effectiveAgeSeconds(nowElapsed)}с",
            )

            if (decision.shouldRing) {
                AlarmScheduler(this).cancelDeadline(alarmId)
                AlarmRingService.startRinging(this, alarm)
                stopEverything()
                return
            }

            startForegroundNotification(alarmId, deadlineMillis, snapshot)
            delay(POLL_INTERVAL_MILLIS)
        }
    }

    private fun ringNow(alarmId: Long) {
        scope.launch {
            val alarm = app().alarmsRepository.alarms.first().firstOrNull { it.id == alarmId }
            AlarmScheduler(this@AlarmWaitService).cancelDeadline(alarmId)
            if (alarm != null) AlarmRingService.startRinging(this@AlarmWaitService, alarm)
            stopEverything()
        }
    }

    /** Користувач вирішив, що сьогодні будильник не потрібен. Наступні дні не чіпаємо. */
    private fun cancelAlarm(alarmId: Long) {
        AlarmScheduler(this).cancelDeadline(alarmId)
        stopEverything()
    }

    private fun startForegroundNotification(
        alarmId: Long,
        deadlineMillis: Long,
        status: AlertsSnapshot?,
    ) {
        val deadline = LocalDateTime.ofInstant(Instant.ofEpochMilli(deadlineMillis), ZoneId.systemDefault())
        val updated = if (status?.alertUids != null) {
            getString(R.string.waiting_alert, LocalTime.now().format(TIME_FORMAT))
        } else {
            getString(R.string.waiting_checking)
        }

        val notification = NotificationCompat.Builder(this, Notifications.CHANNEL_WAITING)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(getString(R.string.waiting_title))
            .setContentText(updated)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    updated + "\n" + getString(
                        R.string.waiting_deadline,
                        deadline.toLocalTime().format(TIME_FORMAT),
                    ),
                ),
            )
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(0, getString(R.string.waiting_ring_now), action(ACTION_RING_NOW, alarmId))
            .addAction(0, getString(R.string.waiting_cancel), action(ACTION_CANCEL, alarmId))
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Notifications.WAITING_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(Notifications.WAITING_NOTIFICATION_ID, notification)
        }
    }

    private fun action(action: String, alarmId: Long): PendingIntent {
        val intent = Intent(this, AlarmWaitService::class.java).apply {
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

    private fun acquireWakeLock(deadlineMillis: Long) {
        val power = getSystemService(PowerManager::class.java) ?: return
        val timeout = (deadlineMillis - System.currentTimeMillis()).coerceIn(0, MAX_WAKE_LOCK_MILLIS)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(timeout)
        }
    }

    private fun app() = application as VidbiyApplication

    private fun stopEverything() {
        // Запис має дійти до диска, навіть якщо служба помре наступної миті,
        // тож веде його scope застосунку, а не наш.
        app().applicationScope.launch { app().settingsRepository.clearPendingWait() }
        pollJob?.cancel()
        pollJob = null
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
        private const val TAG = "VidbiyWait"
        private const val WAKE_LOCK_TAG = "vidbiy:wait"
        private const val MAX_WAKE_LOCK_MILLIS = 6 * 60 * 60 * 1000L
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        /** NFR-3 дозволяє до 2 хв затримки після відбою, тож 30 с дають запас. */
        private const val POLL_INTERVAL_MILLIS = 30_000L

        const val ACTION_START = "ua.vidbiy.app.action.START_WAITING"
        const val ACTION_RING_NOW = "ua.vidbiy.app.action.RING_NOW"
        const val ACTION_CANCEL = "ua.vidbiy.app.action.CANCEL_WAITING"
        const val ACTION_STOP = "ua.vidbiy.app.action.STOP_WAITING"

        private const val EXTRA_ALARM_ID = "alarm_id"
        private const val EXTRA_DEADLINE_MILLIS = "deadline_millis"

        fun startWaiting(context: Context, alarm: Alarm, deadline: LocalDateTime) {
            val deadlineMillis = deadline.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val intent = Intent(context, AlarmWaitService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ALARM_ID, alarm.id)
                putExtra(EXTRA_DEADLINE_MILLIS, deadlineMillis)
            }
            context.startForegroundService(intent)
        }

        /**
         * «Сьогодні не треба»: те саме, що кнопка «Скасувати» в нотифікації.
         * Наступні дні лишаються як були.
         */
        fun cancelWaiting(context: Context, alarmId: Long) {
            val intent = Intent(context, AlarmWaitService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_ALARM_ID, alarmId)
            }
            runCatching { context.startService(intent) }
        }

        /** Викликається, коли будильник уже дзвонить: чекати більше нема чого. */
        fun stop(context: Context) {
            val intent = Intent(context, AlarmWaitService::class.java).apply { action = ACTION_STOP }
            runCatching { context.startService(intent) }
        }
    }
}
