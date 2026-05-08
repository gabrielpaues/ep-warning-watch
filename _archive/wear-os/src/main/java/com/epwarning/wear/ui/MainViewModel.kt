package com.epwarning.wear.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.epwarning.wear.data.AlarmHistoryRepository
import com.epwarning.wear.data.AlarmRecord
import com.epwarning.wear.data.Settings
import com.epwarning.wear.data.SettingsRepository
import com.epwarning.wear.messaging.ConnectionState
import com.epwarning.wear.messaging.PhoneMessenger
import com.epwarning.wear.service.DetectorService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepo = SettingsRepository(app)
    private val alarmRepo = AlarmHistoryRepository(app)
    private val messenger = PhoneMessenger(app)

    val settings: StateFlow<Settings> = settingsRepo.settings.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000),
        Settings(0.5f, 8f, 60f, false),
    )
    val alarms: StateFlow<List<AlarmRecord>> = alarmRepo.alarms.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList(),
    )
    val connection: StateFlow<ConnectionState> = messenger.connectionState().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionState.Disconnected,
    )

    fun setSensitivity(value: Float) {
        viewModelScope.launch { settingsRepo.setSensitivity(value) }
    }
    fun setSustain(value: Float) {
        viewModelScope.launch { settingsRepo.setSustainSeconds(value) }
    }
    fun startMonitoring() {
        viewModelScope.launch { settingsRepo.setMonitoringEnabled(true) }
        DetectorService.start(getApplication())
    }
    fun stopMonitoring() {
        viewModelScope.launch { settingsRepo.setMonitoringEnabled(false) }
        DetectorService.stop(getApplication())
    }
    fun clearHistory() {
        viewModelScope.launch { alarmRepo.clear() }
    }
}
