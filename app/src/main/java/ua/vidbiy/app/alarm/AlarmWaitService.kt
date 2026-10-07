package ua.vidbiy.app.alarm

import android.app.Notification
import android.app.NotificationManager
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
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
import ua.vidbiy.app.data.DecisionEntry
import ua.vidbiy.app.data.DecisionLog
import ua.vidbiy.app.data.PendingWait
import ua.vidbiy.app.data.WaitStatus
import ua.vidbiy.app.data.alertUids
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
 * Очікувань може бути кілька одразу — будильники в різних регіонах і разовий режим. Кожне
 * має власну корутину опитування, сповіщення, wake lock і запис у сховищі, а служба живе, доки
 * є бодай одне. Як «справжнє» foreground-сповіщення Android тримає одне з них ([foregroundId]);
 * решта — звичайні сповіщення, і коли головне зникає, роль переходить до іншого.
 *
 * Служба не єдиний запобіжник: момент, коли будильник здасться (крайній час або доба),
 * окремо зареєстрований у AlarmManager (AlarmScheduler.scheduleDeadline), тож навіть якщо
 * систему занесе й вона прибере цей процес, будильник однаково задзвонить.
 */
class AlarmWaitService : Service() {

    // Обробник лише пише в лог: сам цикл очікування ловить свої збої й дзвонить (pollUntilClear),
    // а необроблений виняток у решті корутин завалив би процес разом із дзвінком, що грає.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "Необроблений виняток у службі очікування", e) },
    )
    private val jobs = mutableMapOf<Long, Job>()
    private val wakeLocks = mutableMapOf<Long, PowerManager.WakeLock>()

    /** Останнє сповіщення кожного очікування: потрібне, щоб передати роль foreground іншому. */
    private val notifications = mutableMapOf<Long, Notification>()

    /** Очікування, чиє сповіщення тримає службу в foreground. */
    private var foregroundId: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val alarmId = intent?.getLongExtra(EXTRA_ALARM_ID, Alarm.NEW_ID) ?: Alarm.NEW_ID

        when (intent?.action) {
            ACTION_START -> {
                val wait = intent.getStringExtra(EXTRA_WAIT)?.let(PendingWait::fromJson) ?: PendingWait(alarmId = alarmId)
                startWaiting(wait)
                // Систему змусили прибити службу, і вона віддає лише останній Intent. Інші
                // очікування лежать у сховищі — піднімаємо й їх, інакше вони втратили б опитування.
                if (flags and START_FLAG_REDELIVERY != 0) resumeOtherWaits(except = wait.alarmId)
            }
            ACTION_SNOOZE -> snooze(alarmId)
            ACTION_CANCEL -> cancelAlarm(alarmId)
            ACTION_STOP -> finishWait(alarmId)
            else -> stopIfIdle()
        }
        // START_REDELIVER_INTENT: якщо систему змусять прибити службу, вона віддасть їй
        // той самий Intent наново — разом із будильником і крайнім часом.
        return START_REDELIVER_INTENT
    }

    private fun startWaiting(requested: PendingWait) {
        val wait = requested.withKnownStart(System.currentTimeMillis())
        if (wait.alarmId == Alarm.NEW_ID) {
            stopIfIdle()
            return
        }

        // Android вимагає показати сповіщення протягом кількох секунд після старту служби,
        // тож перше — ще до того, як прочитали будильник.
        publish(wait.alarmId, buildNotification(alarm = null, wait = wait, status = null, snoozeMinutes = null))
        // Кожен startForegroundService вимагає свого startForeground, навіть якщо сповіщення
        // нового очікування — не те, що тримає службу.
        refreshForeground()
        acquireWakeLock(wait)
        app().applicationScope.launch {
            app().settingsRepository.setPendingWait(wait)
            app().settingsRepository.setWaitStatus(WaitStatus(alarmId = wait.alarmId))
        }

        jobs.remove(wait.alarmId)?.cancel()
        jobs[wait.alarmId] = scope.launch { pollUntilClear(wait) }
    }

    private fun resumeOtherWaits(except: Long) {
        scope.launch {
            for (saved in app().settingsRepository.currentPendingWaits()) {
                if (saved.alarmId == except || saved.alarmId in jobs) continue
                Log.i(TAG, "Відновлюємо очікування після перезапуску служби: будильник=${saved.alarmId}")
                startWaiting(saved)
            }
        }
    }

    /**
     * Будь-який збій у циклі (диск, DataStore, сповіщення) = дзвонимо, а не мовчимо (NFR-1).
     * Без цього необроблений виняток валив би процес, а крайній час у AlarmManager
     * без заданого крайнього часу — це доба.
     */
    private suspend fun pollUntilClear(wait: PendingWait) {
        try {
            pollLoop(wait)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Крок очікування не вдався — дзвонимо (NFR-1): будильник=${wait.alarmId}", e)
            ringAfterFailure(wait)
        }
    }

    private suspend fun ringAfterFailure(wait: PendingWait) = withContext(NonCancellable) {
        // Кожен крок окремо: збій одного (наприклад, диска) не має завадити самому дзвінку.
        runCatching { AlarmScheduler(this@AlarmWaitService).cancelDeadline(wait.alarmId) }
        val alarm = wait.alarm ?: runCatching { app().findAlarm(wait.alarmId) }.getOrNull()
        if (alarm != null) {
            val reason = RingReason(
                kind = RingReason.Kind.APP_FAILURE,
                oneShot = alarm.id == OneShot.ONE_SHOT_ID,
            )
            AlarmRingService.startRinging(this@AlarmWaitService, alarm, reason)
            runCatching {
                app().decisionLog.log(
                    DecisionEntry(at = DecisionLog.now(), event = "ring", alarmId = alarm.id, region = alarm.region?.uid, note = "LOOP_FAILURE: ${reason.kind}"),
                )
            }
        }
        finishWait(wait.alarmId)
    }

    private suspend fun pollLoop(wait: PendingWait) {
        val app = app()
        val baseUrl = app.settingsRepository.proxyBaseUrl()
        val client = app.alertsClient()
        val startedElapsed = SystemClock.elapsedRealtime()
        // Усе, що треба пам'ятати між опитуваннями; рішення — у waitTick (WaitLoop.kt).
        var state = WaitLoopState()
        var previousPollElapsed: Long? = null
        Log.i(TAG, "Чекаємо відбою: будильник=${wait.alarmId}, проксі=$baseUrl")

        app.dataReady.await()
        while (currentCoroutineContext().isActive) {
            val live = app.findAlarm(wait.alarmId)
            if (live == null) {
                // Будильник видалили, поки ми чекали — чекати більше нема для кого.
                AlarmScheduler(this@AlarmWaitService).cancelDeadline(wait.alarmId)
                finishWait(wait.alarmId)
                return
            }

            // «Я жива»: відсуваємо вартового. Якщо службу вб'ють, цього ніхто не зробить,
            // і за WATCHDOG_MILLIS будильник задзвонить сам.
            AlarmScheduler(this@AlarmWaitService)
                .scheduleWatchdog(wait.alarmId, System.currentTimeMillis() + WATCHDOG_MILLIS)

            // Налаштування — ті, з якими очікування почалося (PendingWait.alarm).
            val alarm = wait.alarm ?: live
            val region = alarm.region
            val (fetched, attempt) = client.fetchWithAttempt()
            DebugFailures.throwIfRequested()
            val nowElapsed = SystemClock.elapsedRealtime()
            val nowMillis = System.currentTimeMillis()
            val before = state
            val tick = waitTick(
                state = state,
                fetched = fetched,
                alarm = alarm,
                deadlineMillis = wait.deadlineMillis,
                giveUpAtMillis = wait.giveUpAtMillis(nowMillis),
                startedElapsed = startedElapsed,
                nowElapsed = nowElapsed,
                nowMillis = nowMillis,
            )
            state = tick.state
            val snapshot = tick.snapshot
            val gapSeconds = previousPollElapsed?.let { (nowElapsed - it) / 1000 }
            previousPollElapsed = nowElapsed
            val age = snapshot.effectiveAgeSeconds(nowElapsed)
            val entry = DecisionEntry(
                at = DecisionLog.now(nowMillis),
                event = "poll",
                alarmId = alarm.id,
                region = region?.uid,
                covering = region?.alertUids?.sorted().orEmpty(),
                waitFor = alarm.waitFor.name,
                pauseMinutes = alarm.pauseMinutes,
                ageSeconds = age,
                confirmedAt = snapshot.confirmedAt,
                levels = DecisionLog.describeLevels(region, snapshot.alerts),
                decision = tick.decision.name,
                step = tick.logStep,
                fetch = attempt.outcome,
                fetchMillis = attempt.durationMillis,
                gapSeconds = gapSeconds,
            )
            Log.i(TAG, entry.toString())
            app.decisionLog.log(entry)

            when (val action = tick.action) {
                WaitAction.Retry -> {
                    delay(STARTUP_RETRY_MILLIS)
                    continue
                }
                // Рішення дзвонити вже ухвалене — жодне скасування (зупинка служби посеред шляху)
                // не має його обірвати: будильник, що не задзвонив, гірший (NFR-1).
                is WaitAction.Ring -> withContext(NonCancellable) {
                    val strongest = region?.let { r -> snapshot.alerts?.let { r.strongestLevel(it, alarm.waitFor, nowMillis) } }
                    val reason = ringReasonFor(
                        decision = action.decision,
                        sawAlert = before.sawAlert,
                        placeName = app.placesRepository.current().byId(alarm.placeId)?.name ?: region?.shortTitle,
                        level = strongest?.level ?: app.settingsRepository.currentWaitStatus(wait.alarmId)?.level,
                        allClearAtMillis = before.allClearAtMillis,
                        pauseMinutes = alarm.pauseMinutes,
                        deadlineMillis = wait.deadlineMillis,
                        nowMillis = nowMillis,
                    ).copy(oneShot = alarm.id == OneShot.ONE_SHOT_ID)
                    AlarmScheduler(this@AlarmWaitService).cancelDeadline(wait.alarmId)
                    AlarmRingService.startRinging(this@AlarmWaitService, alarm, reason)
                    app.decisionLog.log(
                        DecisionEntry(at = DecisionLog.now(), event = "ring", alarmId = alarm.id, region = region?.uid, note = reason.kind.name),
                    )
                    finishWait(wait.alarmId)
                }
                is WaitAction.Wait -> {
                    val pausing = state.allClearAtElapsed != null
                    val strongest = region?.let { r -> snapshot.alerts?.let { r.strongestLevel(it, alarm.waitFor, nowMillis) } }
                    val status = WaitStatus(
                        alarmId = alarm.id,
                        level = if (!pausing) strongest?.level else null,
                        reason = if (!pausing) strongest?.reason?.takeIf { it.isNotBlank() } else null,
                        confirmedAtMillis = age?.let { nowMillis - it * 1000 },
                        allClearAtMillis = state.allClearAtMillis,
                        ringAtMillis = state.allClearAtMillis?.plus(alarm.pauseMinutes * 60_000L),
                    )
                    app.settingsRepository.setWaitStatus(status)
                    // Назва місця, а якщо місця немає (обрано напряму чи видалено) — коротка назва регіону.
                    val placeName = app.placesRepository.current().byId(alarm.placeId)?.name ?: region?.shortTitle
                    publish(
                        alarm.id,
                        buildNotification(alarm, wait, status, app.settingsRepository.snoozeMinutes.first(), placeName),
                    )
                    delay(action.delayMillis)
                    continue
                }
            }
            return
        }
    }

    private fun snooze(alarmId: Long) {
        scope.launch {
            val minutes = app().settingsRepository.snoozeMinutes.first()
            AlarmScheduler(this@AlarmWaitService).cancelDeadline(alarmId)
            Snoozes.snooze(this@AlarmWaitService, alarmId, minutes)
            Log.i(TAG, "Відкладено з очікування на $minutes хв: будильник=$alarmId")
            app().decisionLog.log(
                DecisionEntry(at = DecisionLog.now(), event = "snooze_from_wait", alarmId = alarmId, note = "$minutes хв"),
            )
            finishWait(alarmId)
        }
    }

    /** Користувач вирішив, що сьогодні будильник не потрібен. Наступні дні не чіпаємо. */
    private fun cancelAlarm(alarmId: Long) {
        app().decisionLog.log(DecisionEntry(at = DecisionLog.now(), event = "cancel_wait", alarmId = alarmId))
        AlarmScheduler(this).cancelDeadline(alarmId)
        finishWait(alarmId)
    }

    /** Показує сповіщення очікування: головне — через startForeground, решту — звичайним notify. */
    private fun publish(alarmId: Long, notification: Notification) {
        notifications[alarmId] = notification
        if (foregroundId == null) foregroundId = alarmId
        if (foregroundId == alarmId) {
            startForeground(alarmId, notification)
        } else {
            notificationManager().notify(Notifications.waitingNotificationId(alarmId), notification)
        }
    }

    private fun refreshForeground() {
        val id = foregroundId ?: return
        notifications[id]?.let { startForeground(id, it) }
    }

    private fun startForeground(alarmId: Long, notification: Notification) {
        val id = Notifications.waitingNotificationId(alarmId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(id, notification)
        }
    }

    /**
     * Сповіщення очікування (design-spec 3.9): заголовок, рівень кольором, причина,
     * «Дім · оновлено 06:51 · крайній час 08:00». Дії: «Через X хв» відкладає одразу,
     * «Не дзвонити…» лише відкриває список — там скасування утриманням на панелі очікування (NFR-1).
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
            .setSmallIcon(R.drawable.ic_stat_vidbiy)
            .setContentTitle(title)
            .setContentText(body.lines().firstOrNull { it.isNotBlank() })
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(openAlarms(wait.alarmId))
        if (snoozeMinutes != null) {
            builder.addAction(0, getString(R.string.waiting_notif_snooze, snoozeMinutes), action(ACTION_SNOOZE, wait.alarmId))
        }
        // FR-18: не скасовує, а відкриває список — скасування там утриманням на панелі очікування.
        builder.addAction(0, getString(R.string.waiting_notif_skip), openAlarms(wait.alarmId))
        return builder.build()
    }

    private fun isNight(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /** Список будильників, де картка, що чекає, — першою; у кожного сповіщення свій код запиту. */
    private fun openAlarms(alarmId: Long): PendingIntent = PendingIntent.getActivity(
        this,
        REQUEST_OPEN_ALARMS + Notifications.waitingNotificationId(alarmId),
        Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_SHOW_ALARMS)
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

    private fun acquireWakeLock(wait: PendingWait) {
        val power = getSystemService(PowerManager::class.java) ?: return
        wakeLocks.remove(wait.alarmId)?.takeIf { it.isHeld }?.release()
        // До моменту, коли будильник здасться, але не довше доби (PendingWait.MAX_WAIT_MILLIS).
        val timeout = (wait.giveUpAtMillis() - System.currentTimeMillis()).coerceIn(0, PendingWait.MAX_WAIT_MILLIS)
        wakeLocks[wait.alarmId] = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$WAKE_LOCK_TAG:${wait.alarmId}").apply {
            setReferenceCounted(false)
            acquire(timeout)
        }
    }

    private fun app() = application as VidbiyApplication

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    /**
     * Це очікування закінчилося (задзвонило, скасоване, відкладене, будильник видалено).
     * Інші очікування не чіпаємо; службу зупиняємо, лише коли їх не лишилося.
     */
    private fun finishWait(alarmId: Long) {
        // Запис має дійти до диска, навіть якщо служба помре наступної миті,
        // тож веде його scope застосунку, а не наш.
        app().applicationScope.launch { app().settingsRepository.clearPendingWait(alarmId) }
        runCatching { AlarmScheduler(this).cancelWatchdog(alarmId) }
        jobs.remove(alarmId)?.cancel()
        wakeLocks.remove(alarmId)?.takeIf { it.isHeld }?.release()
        notifications.remove(alarmId)

        if (foregroundId == alarmId) {
            foregroundId = notifications.keys.firstOrNull()
            // Не DETACH + cancel: DETACH знімає прапорець foreground асинхронно, cancel приходить раніше
            // й ігнорується, і сповіщення лишається в шторці назавжди. Тому або передаємо роль іншому
            // очікуванню (startForeground з новим id сам знімає старе сповіщення), або знімаємо разом із foreground.
            if (foregroundId != null) refreshForeground() else stopForeground(STOP_FOREGROUND_REMOVE)
            notificationManager().cancel(Notifications.waitingNotificationId(alarmId))
        } else {
            notificationManager().cancel(Notifications.waitingNotificationId(alarmId))
        }
        stopIfIdle()
    }

    private fun stopIfIdle() {
        if (jobs.isNotEmpty() || notifications.isNotEmpty()) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Записи про очікування лишаємо на диску: якщо службу прибила система, їх підніме
        // перезапуск (resumeOtherWaits) або BootReceiver. Стирає їх лише finishWait.
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        wakeLocks.values.forEach { lock -> lock.takeIf { it.isHeld }?.release() }
        wakeLocks.clear()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VidbiyWait"
        private const val WAKE_LOCK_TAG = "vidbiy:wait"
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        private const val REQUEST_OPEN_ALARMS = 7001

        /** Через скільки без «я жива» від служби задзвонить вартовий (десять опитувань підряд пропущено). */
        private const val WATCHDOG_MILLIS = 5 * 60_000L

        const val ACTION_START = "ua.vidbiy.app.action.START_WAITING"
        const val ACTION_SNOOZE = "ua.vidbiy.app.action.SNOOZE_WAITING"
        const val ACTION_CANCEL = "ua.vidbiy.app.action.CANCEL_WAITING"
        const val ACTION_STOP = "ua.vidbiy.app.action.STOP_WAITING"

        private const val EXTRA_ALARM_ID = "alarm_id"
        private const val EXTRA_WAIT = "wait"

        private fun formatTime(hour: Int, minute: Int): String = LocalTime.of(hour, minute).format(TIME_FORMAT)

        private fun formatMillis(millis: Long): String =
            Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime().format(TIME_FORMAT)

        fun startWaiting(context: Context, wait: PendingWait) {
            val intent = Intent(context, AlarmWaitService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ALARM_ID, wait.alarmId)
                // Цілим записом, разом зі знімком будильника: START_REDELIVER_INTENT віддасть його
                // службі наново, якщо систему змусять її прибити.
                putExtra(EXTRA_WAIT, wait.toJson())
            }
            context.startForegroundService(intent)
        }

        /** «Через X хв» з панелі очікування. */
        fun snooze(context: Context, alarmId: Long) = send(context, ACTION_SNOOZE, alarmId)

        /** «Сьогодні не дзвони»: наступні дні лишаються як були. */
        fun cancelWaiting(context: Context, alarmId: Long) = send(context, ACTION_CANCEL, alarmId)

        /** Викликається, коли будильник уже дзвонить: цьому будильнику чекати більше нема чого. */
        fun stop(context: Context, alarmId: Long) = send(context, ACTION_STOP, alarmId)

        private fun send(context: Context, action: String, alarmId: Long) {
            val intent = Intent(context, AlarmWaitService::class.java).apply {
                this.action = action
                putExtra(EXTRA_ALARM_ID, alarmId)
            }
            runCatching { context.startService(intent) }
        }
    }
}
