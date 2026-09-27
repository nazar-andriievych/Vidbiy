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
 * Очікування відбою (FR-8 … FR-18).
 *
 * Поки в обраному регіоні триває тривога, будильник мовчить, а тут висить тиха нотифікація
 * зі станом. На старті до 30 с пробуємо отримати свіжі дані (FR-8), далі опитуємо проксі
 * кожні 30 с; щойно тривоги потрібного рівня не стало — дзвонимо.
 *
 * Служба не єдиний запобіжник: момент, коли будильник здасться (крайній час або доба),
 * окремо зареєстрований у AlarmManager (AlarmScheduler.scheduleDeadline), тож навіть якщо
 * систему занесе й вона прибере цей процес, будильник однаково задзвонить.
 */
class AlarmWaitService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var pollJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val alarmId = intent?.getLongExtra(EXTRA_ALARM_ID, Alarm.NEW_ID) ?: Alarm.NEW_ID

        when (intent?.action) {
            ACTION_START -> startWaiting(
                PendingWait(
                    alarmId = alarmId,
                    deadlineMillis = intent.getLongExtra(EXTRA_DEADLINE_MILLIS, NO_DEADLINE)
                        .takeIf { it != NO_DEADLINE },
                    startedAtMillis = intent.getLongExtra(EXTRA_STARTED_AT_MILLIS, 0L),
                ),
            )
            ACTION_RING_NOW -> ringNow(alarmId)
            ACTION_CANCEL -> cancelAlarm(alarmId)
            else -> stopEverything()
        }
        // START_REDELIVER_INTENT: якщо систему змусять прибити службу, вона віддасть їй
        // той самий Intent наново — разом із будильником і крайнім часом.
        return START_REDELIVER_INTENT
    }

    private fun startWaiting(wait: PendingWait) {
        if (wait.alarmId == Alarm.NEW_ID) {
            stopEverything()
            return
        }

        startForegroundNotification(wait, status = null)
        acquireWakeLock(wait.giveUpAtMillis())
        app().applicationScope.launch { app().settingsRepository.setPendingWait(wait) }

        pollJob?.cancel()
        pollJob = scope.launch { pollUntilClear(wait) }
    }

    private suspend fun pollUntilClear(wait: PendingWait) {
        val app = app()
        val baseUrl = app.settingsRepository.proxyBaseUrl()
        val client = AlertsClient(baseUrl)
        val startedElapsed = SystemClock.elapsedRealtime()
        // Остання відповідь, яка хоч щось знала: невдала спроба її не затирає (FR-15).
        var known: AlertsSnapshot? = null
        Log.i(TAG, "Чекаємо відбою: будильник=${wait.alarmId}, проксі=$baseUrl")

        app.dataReady.await()
        while (currentCoroutineContext().isActive) {
            val alarm = app.alarmsRepository.alarms.first().firstOrNull { it.id == wait.alarmId }
            if (alarm == null) {
                // Будильник видалили, поки ми чекали — чекати більше нема для кого.
                AlarmScheduler(this).cancelDeadline(wait.alarmId)
                stopEverything()
                return
            }

            val region = alarm.region
            val snapshot = client.fetch().orPrevious(known)
            known = snapshot
            val nowElapsed = SystemClock.elapsedRealtime()
            val nowMillis = System.currentTimeMillis()
            var decision = decideRing(
                snapshot = snapshot,
                nowElapsed = nowElapsed,
                nowMillis = nowMillis,
                region = region,
                waitFor = alarm.waitFor,
                pastDeadline = wait.deadlineMillis?.let { nowMillis >= it } ?: false,
            )
            if (!decision.shouldRing && nowMillis >= wait.giveUpAtMillis()) {
                decision = RingDecision.RING_ALERT_TOO_LONG
            }
            Log.i(
                TAG,
                "Рішення=$decision, регіон=${region?.uid} (${region?.title}), рівень=${alarm.waitFor}, " +
                    "тривоги=${region?.let { r -> snapshot.alerts?.let { r.levelsOver(it) } }}, " +
                    "вік=${snapshot.effectiveAgeSeconds(nowElapsed)}с",
            )

            // FR-8: на старті до 30 с даємо мережі шанс, перш ніж дзвонити через брак даних.
            val noFreshData = decision == RingDecision.RING_NO_DATA || decision == RingDecision.RING_STALE
            if (noFreshData && nowElapsed - startedElapsed < STARTUP_WINDOW_MILLIS) {
                delay(STARTUP_RETRY_MILLIS)
                continue
            }

            if (decision.shouldRing) {
                AlarmScheduler(this).cancelDeadline(wait.alarmId)
                AlarmRingService.startRinging(this, alarm)
                stopEverything()
                return
            }

            startForegroundNotification(wait, snapshot)
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

    private fun startForegroundNotification(wait: PendingWait, status: AlertsSnapshot?) {
        val alarmId = wait.alarmId
        val updated = if (status?.isKnown == true) {
            getString(R.string.waiting_alert, LocalTime.now().format(TIME_FORMAT))
        } else {
            getString(R.string.waiting_checking)
        }
        val deadlineLine = wait.deadlineMillis?.let { millis ->
            val deadline = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
            getString(R.string.waiting_deadline, deadline.toLocalTime().format(TIME_FORMAT))
        } ?: getString(R.string.waiting_no_deadline)

        val notification = NotificationCompat.Builder(this, Notifications.CHANNEL_WAITING)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(getString(R.string.waiting_title))
            .setContentText(updated)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(updated + "\n" + deadlineLine),
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

    private fun acquireWakeLock(giveUpAtMillis: Long) {
        val power = getSystemService(PowerManager::class.java) ?: return
        // До моменту, коли будильник здасться, але не довше доби (PendingWait.MAX_WAIT_MILLIS).
        val timeout = (giveUpAtMillis - System.currentTimeMillis()).coerceIn(0, PendingWait.MAX_WAIT_MILLIS)
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
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        /** NFR-3 дозволяє до 2 хв затримки після відбою, тож 30 с дають запас. */
        private const val POLL_INTERVAL_MILLIS = 30_000L

        /** FR-8: скільки на старті пробуємо отримати свіжі дані, перш ніж дзвонити без них. */
        private const val STARTUP_WINDOW_MILLIS = 30_000L
        private const val STARTUP_RETRY_MILLIS = 2_000L
        private const val NO_DEADLINE = -1L

        const val ACTION_START = "ua.vidbiy.app.action.START_WAITING"
        const val ACTION_RING_NOW = "ua.vidbiy.app.action.RING_NOW"
        const val ACTION_CANCEL = "ua.vidbiy.app.action.CANCEL_WAITING"
        const val ACTION_STOP = "ua.vidbiy.app.action.STOP_WAITING"

        private const val EXTRA_ALARM_ID = "alarm_id"
        private const val EXTRA_DEADLINE_MILLIS = "deadline_millis"
        private const val EXTRA_STARTED_AT_MILLIS = "started_at_millis"

        fun startWaiting(context: Context, wait: PendingWait) {
            val intent = Intent(context, AlarmWaitService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ALARM_ID, wait.alarmId)
                putExtra(EXTRA_DEADLINE_MILLIS, wait.deadlineMillis ?: NO_DEADLINE)
                putExtra(EXTRA_STARTED_AT_MILLIS, wait.startedAtMillis)
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
