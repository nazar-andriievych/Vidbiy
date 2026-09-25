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
import ua.vidbiy.app.data.SelectedRegion
import ua.vidbiy.app.data.SettingsRepository

class AlarmsViewModel(
    private val app: VidbiyApplication,
    private val repository: AlarmsRepository,
    private val settings: SettingsRepository,
    private val scheduler: AlarmScheduler,
) : ViewModel() {

    val alarms: StateFlow<List<Alarm>> = repository.alarms
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val region: StateFlow<SelectedRegion?> = settings.selectedRegion
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Будильник, який просто зараз чекає відбою. Без цього він у списку виглядав би
     * вимкненим (одноразовий уже зняв позначку «увімкнено») або «спрацює завтра»,
     * хоча насправді він саме зараз мовчить через тривогу.
     */
    val pendingWait: StateFlow<PendingWait?> = settings.pendingWait
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** «Сьогодні не треба»: те саме, що кнопка «Скасувати» в нотифікації очікування. */
    fun cancelWaiting(alarmId: Long) {
        AlarmWaitService.cancelWaiting(app, alarmId)
    }

    /** Чи відкритий екран вибору регіону. */
    private val _pickingRegion = MutableStateFlow(false)
    val pickingRegion: StateFlow<Boolean> = _pickingRegion.asStateFlow()

    fun startPickRegion() {
        _pickingRegion.value = true
    }

    fun cancelPickRegion() {
        _pickingRegion.value = false
    }

    /** Адреса локального проксі; порожньо — береться робоча. Лише для debug-збірки. */
    val debugProxyUrl: StateFlow<String> = settings.debugProxyUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    fun setDebugProxyUrl(url: String) {
        viewModelScope.launch { settings.setDebugProxyUrl(url) }
    }

    fun selectRegion(region: SelectedRegion) {
        _pickingRegion.value = false
        viewModelScope.launch { settings.setRegion(region) }
    }

    /** Будильник, який зараз редагують. null — показуємо список. */
    private val _draft = MutableStateFlow<Alarm?>(null)
    val draft: StateFlow<Alarm?> = _draft.asStateFlow()

    fun startNew() {
        _draft.value = Alarm(hour = 7, minute = 0)
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
        val alarm = _draft.value ?: return
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

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as VidbiyApplication
                AlarmsViewModel(app, app.alarmsRepository, app.settingsRepository, app.alarmScheduler)
            }
        }
    }
}
