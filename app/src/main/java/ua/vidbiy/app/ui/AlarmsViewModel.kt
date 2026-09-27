package ua.vidbiy.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ua.vidbiy.app.VidbiyApplication
import ua.vidbiy.app.alarm.AlarmScheduler
import ua.vidbiy.app.alarm.AlarmWaitService
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
import ua.vidbiy.app.ui.theme.ThemeMode

/** Повноекранний підекран, що перекриває вкладки (навігаційна бібліотека тут надлишкова). */
sealed interface Overlay {
    /** Регіон для будильника, що зараз редагується. */
    data object AlarmRegion : Overlay

    /** Нове місце: крок 1 — регіон, крок 2 — назва (діалог поверх того ж екрана). */
    data object AddPlace : Overlay

    data class PlaceRegion(val placeId: Long) : Overlay

    /** Екран очікування (design-spec 3.8). */
    data object Waiting : Overlay
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
     * Будильник, який просто зараз чекає відбою. Без цього він у списку виглядав би
     * вимкненим (одноразовий уже зняв позначку «увімкнено») або «спрацює завтра»,
     * хоча насправді він саме зараз мовчить через тривогу.
     */
    val pendingWait: StateFlow<PendingWait?> = settings.pendingWait
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Що зараз бачить служба очікування: рівень, причина, свіжість, пауза. */
    val waitStatus: StateFlow<WaitStatus?> = settings.waitStatus
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** FR-19: тривалість відкладення. */
    val snoozeMinutes: StateFlow<Int> = settings.snoozeMinutes
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsRepository.DEFAULT_SNOOZE_MINUTES)

    fun openWaiting() {
        _overlay.value = Overlay.Waiting
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
    }

    /** FR-32: тема застосунку. */
    val themeMode: StateFlow<ThemeMode> = settings.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.System)

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    /** Адреса локального проксі; порожньо — береться робоча. Лише для debug-збірки. */
    val debugProxyUrl: StateFlow<String> = settings.debugProxyUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    fun setDebugProxyUrl(url: String) {
        viewModelScope.launch { settings.setDebugProxyUrl(url) }
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
        _draft.value = alarm
    }

    fun updateDraft(transform: (Alarm) -> Alarm) {
        _draft.value = _draft.value?.let(transform)
    }

    fun cancelEdit() {
        _draft.value = null
    }

    fun saveDraft() {
        val alarm = _draft.value?.copy(enabled = true) ?: return
        _draft.value = null
        viewModelScope.launch { scheduler.applyEdit(repository.save(alarm)) }
    }

    fun deleteDraft() {
        val alarm = _draft.value ?: return
        _draft.value = null
        if (alarm.id != Alarm.NEW_ID) {
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
