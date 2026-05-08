package com.epwarning.wear.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.alarmDataStore by preferencesDataStore(name = "ep_warning_alarms")

class AlarmHistoryRepository(private val context: Context) {

    val alarms: Flow<List<AlarmRecord>> = context.alarmDataStore.data.map { prefs ->
        val raw = prefs[KEY_ALARMS] ?: return@map emptyList()
        runCatching { json.decodeFromString<List<AlarmRecord>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun add(record: AlarmRecord) {
        context.alarmDataStore.edit { prefs ->
            val existing = prefs[KEY_ALARMS]?.let {
                runCatching { json.decodeFromString<List<AlarmRecord>>(it) }.getOrDefault(emptyList())
            } ?: emptyList()
            val updated = (listOf(record) + existing).take(MAX_RECORDS)
            prefs[KEY_ALARMS] = json.encodeToString<List<AlarmRecord>>(updated)
        }
    }

    suspend fun clear() {
        context.alarmDataStore.edit { it.remove(KEY_ALARMS) }
    }

    companion object {
        private const val MAX_RECORDS = 50
        private val KEY_ALARMS: Preferences.Key<String> = stringPreferencesKey("alarms_json")
        private val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
data class AlarmRecord(
    val id: String,
    val triggeredAtEpochMs: Long,
    val peakIntensity: Float,
    val sustainedSeconds: Float,
    val deliveredToPhone: Boolean,
    val cancelledByUser: Boolean = false,
)
