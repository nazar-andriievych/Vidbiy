package ua.vidbiy.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import android.os.SystemClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ua.vidbiy.app.VidbiyApplication
import ua.vidbiy.app.alarm.AlarmScheduler
import ua.vidbiy.app.alarm.AlarmWaitService
import ua.vidbiy.app.alarm.OneShot
import ua.vidbiy.app.alarm.OneShotCheck
import ua.vidbiy.app.alarm.decideRing
import ua.vidbiy.app.alarm.nextTriggerAt
import ua.vidbiy.app.alarm.hasFreshYellow
import ua.vidbiy.app.alarm.oneShotCheck
import ua.vidbiy.app.data.AlertsClient
import ua.vidbiy.app.data.AlertsSnapshot
import ua.vidbiy.app.data.DecisionEntry
import ua.vidbiy.app.data.DecisionLog
import ua.vidbiy.app.data.WaitFor
import ua.vidbiy.app.data.Alarm
import ua.vidbiy.app.data.AlarmsRepository
import ua.vidbiy.app.data.PendingWait
import ua.vidbiy.app.data.Place
import ua.vidbiy.app.data.PlacesEditor
import ua.vidbiy.app.data.PlacesRepository
import ua.vidbiy.app.data.PlacesState
import ua.vidbiy.app.data.SelectedRegion
import ua.vidbiy.app.data.SettingsRepository
import ua.vidbiy.app.data.WaitStatus
import ua.vidbiy.app.data.withoutPastDate
import ua.vidbiy.app.ui.theme.ThemeMode
import java.time.LocalDateTime

/** Повноекранний підекран, що перекриває вкладки (навігаційна бібліотека тут надлишкова). */
sealed interface Overlay {
    /** Регіон для будильника, що зараз редагується. */
    data object AlarmRegion : Overlay

    /** Нове місце: крок 1 — регіон, крок 2 — назва (діалог поверх того ж екрана). */
    data object AddPlace : Overlay

    data class PlaceRegion(val placeId: Long) : Overlay

    /** Екран очікування (design-spec 3.8) цього будильника; [alarmId] null — будь-якого, що чекає. */
    data class Waiting(val alarmId: Long?) : Overlay

    /** Екран дозволів (design-spec 3.7). */
    data object Permissions : Overlay
}

class AlarmsViewModel(
    private val app: VidbiyApplication,
    private val repository: AlarmsRepository,
    private val settings: SettingsRepository,
    private val placesRepository: PlacesRepository,
    private val placesEditor: PlacesEditor,
    private val scheduler: AlarmScheduler,
) : ViewModel() {

    val alarms: StateFlow<List<Alarm>> = repository.alarms
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Eagerly: основне місце має бути під рукою в ту мить, коли натиснули «Новий будильник». */
    val places: StateFlow<PlacesState> = placesRepository.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, PlacesState())

    /**
     * Будильники (і разовий режим), що просто зараз чекають відбою; їх може бути кілька.
     * Без цього будильник у списку виглядав би вимкненим (одноразовий уже зняв позначку
     * «увімкнено») або «спрацює завтра», хоча насправді він саме зараз мовчить через тривогу.
     */
    val pendingWaits: StateFlow<List<PendingWait>> = settings.pendingWaits
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Що зараз бачить служба про кожне очікування: рівень, причина, свіжість, пауза. */
    val waitStatuses: StateFlow<List<WaitStatus>> = settings.waitStatuses
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** FR-19: тривалість відкладення. */
    val snoozeMinutes: StateFlow<Int> = settings.snoozeMinutes
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsRepository.DEFAULT_SNOOZE_MINUTES)

    // ---- Разовий режим «Розбуди після відбою» (FR-22 … FR-25) ----

    val oneShotWaitFor: StateFlow<WaitFor> = settings.oneShotWaitFor
        .stateIn(viewModelScope, SharingStarted.Eagerly, WaitFor.RED_AND_YELLOW)

    val oneShotPauseMinutes: StateFlow<Int> = settings.oneShotPauseMinutes
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    fun setOneShotWaitFor(waitFor: WaitFor) {
        viewModelScope.launch { settings.setOneShotWaitFor(waitFor) }
    }

    fun setOneShotPauseMinutes(minutes: Int) {
        viewModelScope.launch { settings.setOneShotPauseMinutes(minutes) }
    }

    /** Віртуальний будильник режиму — для екрана очікування. */
    val oneShotAlarm: StateFlow<Alarm> = combine(places, oneShotWaitFor, oneShotPauseMinutes) { p, waitFor, pause ->
        OneShot.alarm(p.primary, waitFor, pause)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, OneShot.alarm(null, WaitFor.RED_AND_YELLOW, 0))

    private val _oneShotRow = MutableStateFlow(OneShotRowState.IDLE)
    val oneShotRow: StateFlow<OneShotRowState> = _oneShotRow.asStateFlow()

    /** Режим щойно ввімкнули: екран очікування показує «Увімкнено · Можна спати». */
    private val _oneShotJustEnabled = MutableStateFlow(false)
    val oneShotJustEnabled: StateFlow<Boolean> = _oneShotJustEnabled.asStateFlow()

    private var oneShotJob: Job? = null

    /**
     * FR-23: та сама перевірка, що в момент будильника, — до 30 с спроб. Тривога є —
     * починаємо очікування; немає тривоги чи даних — показуємо це в рядку ~10 с.
     */
    fun startOneShot() {
        // Знімок на момент натиску: очікування доживе з ним, навіть якщо основне місце
        // чи налаштування режиму змінять посеред тривоги (PendingWait.alarm).
        val snapshot = OneShot.alarm(places.value.primary, oneShotWaitFor.value, oneShotPauseMinutes.value)
        val region = snapshot.region ?: return
        oneShotJob?.cancel()
        _oneShotRow.value = OneShotRowState.CHECKING
        oneShotJob = viewModelScope.launch {
            val client = AlertsClient(settings.proxyBaseUrl())
            val started = SystemClock.elapsedRealtime()
            var known: AlertsSnapshot? = null
            var check: OneShotCheck
            while (true) {
                val (fetched, attempt) = client.fetchWithAttempt()
                known = fetched.orPrevious(known)
                val nowMillis = System.currentTimeMillis()
                val decision = decideRing(
                    snapshot = known,
                    nowElapsed = SystemClock.elapsedRealtime(),
                    nowMillis = nowMillis,
                    region = region,
                    waitFor = snapshot.waitFor,
                    pastDeadline = false,
                )
                check = oneShotCheck(decision, yellowActive = region.hasFreshYellow(known.alerts, nowMillis))
                app.decisionLog.log(
                    DecisionEntry(
                        at = DecisionLog.now(nowMillis),
                        event = "one_shot_check",
                        alarmId = OneShot.ONE_SHOT_ID,
                        region = region.uid,
                        covering = region.coveringUids.sorted(),
                        waitFor = snapshot.waitFor.name,
                        ageSeconds = known.effectiveAgeSeconds(SystemClock.elapsedRealtime()),
                        confirmedAt = known.confirmedAt,
                        levels = DecisionLog.describeLevels(region, known.alerts),
                        decision = decision.name,
                        step = check.name,
                        fetch = attempt.outcome,
                        fetchMillis = attempt.durationMillis,
                    ),
                )
                if (check != OneShotCheck.NO_DATA || SystemClock.elapsedRealtime() - started >= ONE_SHOT_CHECK_MILLIS) break
                delay(ONE_SHOT_RETRY_MILLIS)
            }
            when (check) {
                OneShotCheck.ALERT -> {
                    val wait = PendingWait(
                        alarmId = OneShot.ONE_SHOT_ID,
                        startedAtMillis = System.currentTimeMillis(),
                        alarm = snapshot,
                    )
                    scheduler.scheduleDeadline(OneShot.ONE_SHOT_ID, wait.giveUpAtMillis())
                    AlarmWaitService.startWaiting(app, wait)
                    _oneShotRow.value = OneShotRowState.IDLE
                    _oneShotJustEnabled.value = true
                    _overlay.value = Overlay.Waiting(OneShot.ONE_SHOT_ID)
                }
                OneShotCheck.NO_ALERT, OneShotCheck.ONLY_YELLOW, OneShotCheck.NO_DATA -> {
                    _oneShotRow.value = when (check) {
                        OneShotCheck.NO_ALERT -> OneShotRowState.NO_ALERT
                        OneShotCheck.ONLY_YELLOW -> OneShotRowState.ONLY_YELLOW
                        else -> OneShotRowState.NO_DATA
                    }
                    // Відкриті питання requirements: повідомлення тримається ~10 с.
                    delay(ONE_SHOT_MESSAGE_MILLIS)
                    _oneShotRow.value = OneShotRowState.IDLE
                }
            }
        }
    }

    fun cancelOneShotCheck() {
        oneShotJob?.cancel()
        _oneShotRow.value = OneShotRowState.IDLE
    }

    fun setSnoozeMinutes(minutes: Int) {
        viewModelScope.launch { settings.setSnoozeMinutes(minutes) }
    }

    fun openPermissions() {
        _overlay.value = Overlay.Permissions
    }

    fun openWaiting(alarmId: Long? = null) {
        _overlay.value = Overlay.Waiting(alarmId)
    }

    /** «Сьогодні не дзвони» (утриманням на екрані очікування). */
    fun cancelWaiting(alarmId: Long) {
        _overlay.value = null
        AlarmWaitService.cancelWaiting(app, alarmId)
    }

    /** «Подзвони через X хв»: задзвонить через X хв, навіть якщо тривога триває (FR-20). */
    fun snoozeWaiting(alarmId: Long) {
        _overlay.value = null
        AlarmWaitService.snooze(app, alarmId)
    }

    private val _overlay = MutableStateFlow<Overlay?>(null)
    val overlay: StateFlow<Overlay?> = _overlay.asStateFlow()

    fun closeOverlay() {
        _overlay.value = null
        _oneShotJustEnabled.value = false
    }

    /** FR-32: тема застосунку. */
    val themeMode: StateFlow<ThemeMode> = settings.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.System)

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }


    // ---- Редагування будильника ----

    /** Будильник, який зараз редагують. null — показуємо вкладки. */
    private val _draft = MutableStateFlow<Alarm?>(null)
    val draft: StateFlow<Alarm?> = _draft.asStateFlow()

    /** FR-7: завжди стандартні значення; регіон — основне місце. */
    fun startNew() {
        val primary = places.value.primary
        _draft.value = Alarm(hour = 7, minute = 0, region = primary?.region, placeId = primary?.id)
    }

    fun startEdit(alarm: Alarm) {
        _draft.value = alarm.withoutPastDate(LocalDateTime.now())
    }

    fun updateDraft(transform: (Alarm) -> Alarm) {
        _draft.value = _draft.value?.let(transform)
    }

    fun cancelEdit() {
        _draft.value = null
    }

    /**
     * FR-7b: чи збереження чернетки припинить очікування відбою. Так — якщо цей будильник
     * просто зараз чекає і в ньому щось змінили (без змін «Зберегти» нічого не зупиняє).
     */
    fun draftStopsWaiting(): Boolean {
        val draft = _draft.value ?: return false
        if (pendingWaits.value.none { it.alarmId == draft.id }) return false
        val saved = alarms.value.firstOrNull { it.id == draft.id } ?: return false
        return saved != draft.copy(enabled = saved.enabled)
    }

    /** Коли спрацює щойно збережений будильник — для повідомлення «Спрацює завтра о 06:45». */
    private val _savedNextRing = MutableStateFlow<LocalDateTime?>(null)
    val savedNextRing: StateFlow<LocalDateTime?> = _savedNextRing.asStateFlow()

    fun consumeSavedNextRing() {
        _savedNextRing.value = null
    }

    fun saveDraft() {
        val alarm = _draft.value?.copy(enabled = true) ?: return
        // Дата й час, що вже минули: «Зберегти» на екрані вимкнене, це лише страховка.
        val next = alarm.nextTriggerAt(LocalDateTime.now()) ?: return
        // FR-7b: змінений будильник більше не чекає за старими налаштуваннями.
        if (draftStopsWaiting()) AlarmWaitService.cancelWaiting(app, alarm.id)
        _draft.value = null
        _savedNextRing.value = next
        viewModelScope.launch { scheduler.applyEdit(repository.save(alarm)) }
    }

    fun deleteDraft() {
        val alarm = _draft.value ?: return
        _draft.value = null
        if (alarm.id != Alarm.NEW_ID) {
            if (pendingWaits.value.any { it.alarmId == alarm.id }) AlarmWaitService.cancelWaiting(app, alarm.id)
            scheduler.cancel(alarm.id)
            viewModelScope.launch { repository.delete(alarm.id) }
        }
    }

    fun setEnabled(alarm: Alarm, enabled: Boolean) {
        // schedule() сам скасовує спрацювання вимкненого будильника.
        scheduler.schedule(alarm.copy(enabled = enabled))
        viewModelScope.launch { repository.setEnabled(alarm.id, enabled) }
    }

    fun startPickAlarmRegion() {
        _overlay.value = Overlay.AlarmRegion
    }

    fun setDraftRegion(pick: RegionPick) {
        updateDraft { it.copy(region = pick.region, placeId = pick.placeId) }
        _overlay.value = null
    }

    // ---- Мої місця ----

    fun startAddPlace() {
        _overlay.value = Overlay.AddPlace
    }

    fun addPlace(name: String, region: SelectedRegion) {
        _overlay.value = null
        viewModelScope.launch { placesRepository.add(name, region) }
    }

    fun renamePlace(place: Place, name: String) {
        viewModelScope.launch { placesRepository.rename(place.id, name) }
    }

    fun makePrimary(place: Place) {
        viewModelScope.launch { placesRepository.setPrimary(place.id) }
    }

    fun startChangePlaceRegion(place: Place) {
        _overlay.value = Overlay.PlaceRegion(place.id)
    }

    fun changePlaceRegion(placeId: Long, region: SelectedRegion) {
        _overlay.value = null
        viewModelScope.launch { placesEditor.changeRegion(placeId, region) }
    }

    fun deletePlace(place: Place) {
        viewModelScope.launch { placesEditor.delete(place.id) }
    }

    companion object {
        private const val ONE_SHOT_CHECK_MILLIS = 30_000L
        private const val ONE_SHOT_RETRY_MILLIS = 2_000L
        private const val ONE_SHOT_MESSAGE_MILLIS = 10_000L

        val Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as VidbiyApplication
                AlarmsViewModel(
                    app,
                    app.alarmsRepository,
                    app.settingsRepository,
                    app.placesRepository,
                    app.placesEditor,
                    app.alarmScheduler,
                )
            }
        }
    }
}
