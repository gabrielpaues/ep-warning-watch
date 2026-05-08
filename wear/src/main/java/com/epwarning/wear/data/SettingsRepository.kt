package com.epwarning.wear.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.epwarning.wear.detection.DetectorConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "ep_warning_settings")

class SettingsRepository(private val context: Context) {

    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            sensitivity = prefs[KEY_SENSITIVITY] ?: 0.5f,
            sustainSeconds = prefs[KEY_SUSTAIN_S] ?: 8f,
            cooldownSeconds = prefs[KEY_COOLDOWN_S] ?: 60f,
            countdownSeconds = prefs[KEY_COUNTDOWN_S] ?: 5f,
            monitoringEnabled = prefs[KEY_MONITORING] ?: false,
        )
    }

    suspend fun setSensitivity(value: Float) = context.dataStore.edit { it[KEY_SENSITIVITY] = value }
    suspend fun setSustainSeconds(value: Float) = context.dataStore.edit { it[KEY_SUSTAIN_S] = value }
    suspend fun setCountdownSeconds(value: Float) = context.dataStore.edit { it[KEY_COUNTDOWN_S] = value }
    suspend fun setMonitoringEnabled(enabled: Boolean) = context.dataStore.edit { it[KEY_MONITORING] = enabled }

    companion object {
        private val KEY_SENSITIVITY: Preferences.Key<Float> = floatPreferencesKey("sensitivity")
        private val KEY_SUSTAIN_S: Preferences.Key<Float> = floatPreferencesKey("sustain_seconds")
        private val KEY_COOLDOWN_S: Preferences.Key<Float> = floatPreferencesKey("cooldown_seconds")
        private val KEY_COUNTDOWN_S: Preferences.Key<Float> = floatPreferencesKey("countdown_seconds")
        private val KEY_MONITORING: Preferences.Key<Boolean> = booleanPreferencesKey("monitoring_enabled")
    }
}

data class Settings(
    val sensitivity: Float,
    val sustainSeconds: Float,
    val cooldownSeconds: Float,
    val countdownSeconds: Float,
    val monitoringEnabled: Boolean,
) {
    fun toDetectorConfig(): DetectorConfig = DetectorConfig(
        sensitivity = sensitivity,
        sustainSeconds = sustainSeconds,
        windowSeconds = 2f,
        cooldownSeconds = cooldownSeconds,
    )
}
