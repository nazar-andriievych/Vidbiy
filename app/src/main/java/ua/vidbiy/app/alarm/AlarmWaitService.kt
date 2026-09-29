package ua.vidbiy.app.alarm

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.vidbiy.app.MainActivity
import ua.vidbiy.app.R
import ua.vidbiy.app.VidbiyApplication
import ua.vidbiy.app.data.Alarm
import ua.vidbiy.app.data.AlertLevel
import ua.vidbiy.app.data.AlertsClient
import ua.vidbiy.app.data.AlertsSnapshot
import ua.vidbiy.app.data.DecisionEntry
import ua.vidbiy.app.data.DecisionLog
import ua.vidbiy.app.data.PendingWait
import ua.vidbiy.app.data.WaitStatus
import ua.vidbiy.app.data.shortTitle
import ua.vidbiy.app.ui.theme.DarkAlertColors
import ua.vidbiy.app.ui.theme.LightAlertColors
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Очікування відбою (FR-8 … FR-18).
 *
 * Поки в обраному регіоні триває тривога, будильник мовчить, а тут висить тихе сповіщення
 * зі станом. На старті до 30 с пробуємо отримати свіжі дані (FR-8), далі опитуємо проксі
 * кожні 30 с. Відбій — чекаємо паузу N хв (FR-14), і якщо тривога не повернулася — дзвонимо.
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
            ACTION_SNOOZE -> snooze(alarmId)
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

        // Android вимагає показати сповіщення протягом кількох секунд після старту служби,
        // тож перше — ще до того, як прочитали будильник.
        startForeground(buildNotification(alarm = null, wait = wait, status = null, snoozeMinutes = null))
        acquireWakeLock(wait.giveUpAtMillis())
        app().applicationScope.launch {
            app().settingsRepository.setPendingWait(wait)
            app().settingsRepository.setWaitStatus(WaitStatus(alarmId = wait.alarmId))
        }

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
        // Чи бачили тривогу: без неї відбій до часу будильника нічого не означає (FR-10).
        var sawAlert = false
        var allClearAtElapsed: Long? = null
        var allClearAtMillis: Long? = null
        var previousPollElapsed: Long? = null
        Log.i(TAG, "Чекаємо відбою: будильник=${wait.alarmId}, проксі=$baseUrl")

        app.dataReady.await()
        while (currentCoroutineContext().isActive) {
            val alarm = app.findAlarm(wait.alarmId)
            if (alarm == null) {
                // Будильник видалили, поки ми чекали — чекати більше нема для кого.
                AlarmScheduler(this@AlarmWaitService).cancelDeadline(wait.alarmId)
                stopEverything()
                return
            }

            val region = alarm.region
            val (fetched, attempt) = client.fetchWithAttempt()
            val snapshot = fetched.orPrevious(known)
            known = snapshot
            val nowElapsed = SystemClock.elapsedRealtime()
            val gapSeconds = previousPollElapsed?.let { (nowElapsed - it) / 1000 }
            previousPollElapsed = nowElapsed
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
            val age = snapshot.effectiveAgeSeconds(nowElapsed)
            fun logPoll(step: String) {
                val entry = DecisionEntry(
                    at = DecisionLog.now(nowMillis),
                    event = "poll",
                    alarmId = alarm.id,
                    region = region?.uid,
                    covering = region?.coveringUids?.sorted().orEmpty(),
                    waitFor = alarm.waitFor.name,
                    pauseMinutes = alarm.pauseMinutes,
                    ageSeconds = age,
                    confirmedAt = snapshot.confirmedAt,
                    levels = DecisionLog.describeLevels(region, snapshot.alerts),
                    decision = decision.name,
                    step = step,
                    fetch = attempt.outcome,
                    fetchMillis = attempt.durationMillis,
                    gapSeconds = gapSeconds,
                )
                Log.i(TAG, entry.toString())
                app.decisionLog.log(entry)
            }

            // FR-8: на старті до 30 с даємо мережі шанс, перш ніж дзвонити через брак даних.
            val noFreshData = decision == RingDecision.RING_NO_DATA || decision == RingDecision.RING_STALE
            if (noFreshData && !sawAlert && nowElapsed - startedElapsed < STARTUP_WINDOW_MILLIS) {
                logPoll("retry")
                delay(STARTUP_RETRY_MILLIS)
                continue
            }

            val step = nextWaitStep(decision, sawAlert, allClearAtElapsed, alarm.pauseMinutes, nowElapsed)
            logPoll(
                when {
                    step is WaitStep.Ring -> "ring"
                    (step as WaitStep.Wait).allClearAtElapsed != null -> "pause"
                    else -> "wait"
                },
            )
            // Рішення дзвонити вже ухвалене — жодне скасування (зупинка служби посеред шляху)
            // не має його обірвати: будильник, що не задзвонив, гірший (NFR-1).
            if (step is WaitStep.Ring) withContext(NonCancellable) {
                val strongest = region?.let { r -> snapshot.alerts?.let { r.strongestLevel(it, alarm.waitFor, nowMillis) } }
                val reason = ringReasonFor(
                    decision = step.reason,
                    sawAlert = sawAlert,
                    placeName = app.placesRepository.current().byId(alarm.placeId)?.name ?: region?.shortTitle,
                    level = strongest?.level ?: app.settingsRepository.waitStatus.first()?.level,
                    allClearAtMillis = allClearAtMillis,
                    pauseMinutes = alarm.pauseMinutes,
                    deadlineMillis = wait.deadlineMillis,
                    nowMillis = nowMillis,
                ).copy(oneShot = alarm.id == OneShot.ONE_SHOT_ID)
                AlarmScheduler(this@AlarmWaitService).cancelDeadline(wait.alarmId)
                AlarmRingService.startRinging(this@AlarmWaitService, alarm, reason)
                app.decisionLog.log(
                    DecisionEntry(at = DecisionLog.now(), event = "ring", alarmId = alarm.id, region = region?.uid, note = reason.kind.name),
                )
                stopEverything()
            }
            if (step is WaitStep.Ring) return
            step as WaitStep.Wait
            if (decision == RingDecision.KEEP_WAITING) sawAlert = true
            if (step.allClearAtElapsed == null) {
                allClearAtMillis = null
            } else if (allClearAtElapsed == null) {
                allClearAtMillis = nowMillis
            }
            allClearAtElapsed = step.allClearAtElapsed

            val strongest = region?.let { r -> snapshot.alerts?.let { r.strongestLevel(it, alarm.waitFor, nowMillis) } }
            val status = WaitStatus(
                alarmId = alarm.id,
                level = if (allClearAtElapsed == null) strongest?.level else null,
                reason = if (allClearAtElapsed == null) strongest?.reason?.takeIf { it.isNotBlank() } else null,
                confirmedAtMillis = age?.let { nowMillis - it * 1000 },
                allClearAtMillis = allClearAtMillis,
                ringAtMillis = allClearAtMillis?.plus(alarm.pauseMinutes * 60_000L),
            )
            app.settingsRepository.setWaitStatus(status)
            // Назва місця, а якщо місця немає (обрано напряму чи видалено) — коротка назва регіону.
            val placeName = app.placesRepository.current().byId(alarm.placeId)?.name ?: region?.shortTitle
            startForeground(
                buildNotification(alarm, wait, status, app.settingsRepository.snoozeMinutes.first(), placeName),
            )

            // Під час паузи будимося рівно до її кінця, якщо він ближчий за звичайне опитування.
            val untilPauseEnd = allClearAtElapsed?.let { it + alarm.pauseMinutes * 60_000L - nowElapsed }
            delay(untilPauseEnd?.coerceIn(1_000L, POLL_INTERVAL_MILLIS) ?: POLL_INTERVAL_MILLIS)
        }
    }

    /** FR-18/FR-20: «Подзвони через X хв» — задзвонить через X хв незалежно від тривоги. */
    private fun snooze(alarmId: Long) {
        scope.launch {
            val minutes = app().settingsRepository.snoozeMinutes.first()
            AlarmScheduler(this@AlarmWaitService).cancelDeadline(alarmId)
            AlarmScheduler(this@AlarmWaitService).snooze(alarmId, minutes)
            Log.i(TAG, "Відкладено з очікування на $minutes хв: будильник=$alarmId")
            app().decisionLog.log(
                DecisionEntry(at = DecisionLog.now(), event = "snooze_from_wait", alarmId = alarmId, note = "$minutes хв"),
            )
            stopEverything()
        }
    }

    /** Користувач вирішив, що сьогодні будильник не потрібен. Наступні дні не чіпаємо. */
    private fun cancelAlarm(alarmId: Long) {
        app().decisionLog.log(DecisionEntry(at = DecisionLog.now(), event = "cancel_wait", alarmId = alarmId))
        AlarmScheduler(this).cancelDeadline(alarmId)
        stopEverything()
    }

    private fun startForeground(notification: Notification) {
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

    /**
     * Сповіщення очікування (design-spec 3.9): заголовок, рівень кольором, причина,
     * «Дім · оновлено 06:51 · крайній час 08:00». Дії: «Через X хв» відкладає одразу,
     * «Не дзвонити…» лише відкриває екран очікування — там скасування утриманням (NFR-1).
     */
    private fun buildNotification(
        alarm: Alarm?,
        wait: PendingWait,
        status: WaitStatus?,
        snoozeMinutes: Int?,
        placeName: String? = null,
    ): Notification {
        val time = alarm?.let { formatTime(it.hour, it.minute) }
        val pauseRingAt = status?.ringAtMillis
        val oneShot = wait.alarmId == OneShot.ONE_SHOT_ID
        val title = when {
            oneShot && pauseRingAt != null -> getString(R.string.one_shot_notif_title_pause, formatMillis(pauseRingAt))
            oneShot -> getString(R.string.one_shot_notif_title)
            time == null -> getString(R.string.waiting_title_checking_generic)
            pauseRingAt != null -> getString(R.string.waiting_notif_title_pause, time, formatMillis(pauseRingAt))
            status?.level != null -> getString(R.string.waiting_notif_title, time)
            else -> getString(R.string.waiting_notif_title_checking, time)
        }

        val body = SpannableStringBuilder()
        status?.level?.let { level ->
            val colors = if (isNight()) DarkAlertColors else LightAlertColors
            val color = if (level == AlertLevel.RED) colors.redText else colors.yellowText
            val start = body.length
            body.append(getString(if (level == AlertLevel.RED) R.string.level_red_full else R.string.level_yellow_full))
            body.setSpan(ForegroundColorSpan(color.toArgb()), start, body.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            body.append('\n')
        }
        status?.reason?.let { body.append(it).append('\n') }
        val meta = listOfNotNull(
            placeName,
            status?.confirmedAtMillis?.let { getString(R.string.waiting_notif_updated, formatMillis(it)) },
            wait.deadlineMillis?.let { getString(R.string.waiting_notif_deadline, formatMillis(it)) },
        ).joinToString(" · ")
        body.append(meta)

        val builder = NotificationCompat.Builder(this, Notifications.CHANNEL_WAITING)
            .setSmallIcon(R.drawable.ic_bedtime)
            .setContentTitle(title)
            .setContentText(body.lines().firstOrNull { it.isNotBlank() })
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(openWaitingScreen())
        if (snoozeMinutes != null) {
            builder.addAction(0, getString(R.string.waiting_notif_snooze, snoozeMinutes), action(ACTION_SNOOZE, wait.alarmId))
        }
        builder.addAction(0, getString(R.string.waiting_notif_skip), openWaitingScreen())
        return builder.build()
    }

    private fun isNight(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun openWaitingScreen(): PendingIntent = PendingIntent.getActivity(
        this,
        REQUEST_OPEN_WAITING,
        Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_SHOW_WAITING)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

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
        private const val REQUEST_OPEN_WAITING = 7001

        /** NFR-3 дозволяє до 2 хв затримки після відбою, тож 30 с дають запас. */
        private const val POLL_INTERVAL_MILLIS = 30_000L

        /** FR-8: скільки на старті пробуємо отримати свіжі дані, перш ніж дзвонити без них. */
        private const val STARTUP_WINDOW_MILLIS = 30_000L
        private const val STARTUP_RETRY_MILLIS = 2_000L
        private const val NO_DEADLINE = -1L

        const val ACTION_START = "ua.vidbiy.app.action.START_WAITING"
        const val ACTION_SNOOZE = "ua.vidbiy.app.action.SNOOZE_WAITING"
        const val ACTION_CANCEL = "ua.vidbiy.app.action.CANCEL_WAITING"
        const val ACTION_STOP = "ua.vidbiy.app.action.STOP_WAITING"

        private const val EXTRA_ALARM_ID = "alarm_id"
        private const val EXTRA_DEADLINE_MILLIS = "deadline_millis"
        private const val EXTRA_STARTED_AT_MILLIS = "started_at_millis"

        private fun formatTime(hour: Int, minute: Int): String = LocalTime.of(hour, minute).format(TIME_FORMAT)

        private fun formatMillis(millis: Long): String =
            Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime().format(TIME_FORMAT)

        fun startWaiting(context: Context, wait: PendingWait) {
            val intent = Intent(context, AlarmWaitService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ALARM_ID, wait.alarmId)
                putExtra(EXTRA_DEADLINE_MILLIS, wait.deadlineMillis ?: NO_DEADLINE)
                putExtra(EXTRA_STARTED_AT_MILLIS, wait.startedAtMillis)
            }
            context.startForegroundService(intent)
        }

        /** «Подзвони через X хв» з екрана очікування. */
        fun snooze(context: Context, alarmId: Long) = send(context, ACTION_SNOOZE, alarmId)

        /** «Сьогодні не дзвони»: наступні дні лишаються як були. */
        fun cancelWaiting(context: Context, alarmId: Long) = send(context, ACTION_CANCEL, alarmId)

        /** Викликається, коли будильник уже дзвонить: чекати більше нема чого. */
        fun stop(context: Context) {
            val intent = Intent(context, AlarmWaitService::class.java).apply { action = ACTION_STOP }
            runCatching { context.startService(intent) }
        }

        private fun send(context: Context, action: String, alarmId: Long) {
            val intent = Intent(context, AlarmWaitService::class.java).apply {
                this.action = action
                putExtra(EXTRA_ALARM_ID, alarmId)
            }
            runCatching { context.startService(intent) }
        }
    }
}
