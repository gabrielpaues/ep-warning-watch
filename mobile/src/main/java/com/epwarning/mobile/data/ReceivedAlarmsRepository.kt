package com.epwarning.mobile.data

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

private val Context.alarmsDataStore by preferencesDataStore(name = "ep_warning_received")

class ReceivedAlarmsRepository(private val context: Context) {

    val alarms: Flow<List<ReceivedAlarm>> = context.alarmsDataStore.data.map { prefs ->
        decode(prefs[KEY])
    }

    suspend fun add(alarm: ReceivedAlarm) {
        context.alarmsDataStore.edit { prefs ->
            val existing = decode(prefs[KEY])
            val updated = (listOf(alarm) + existing).take(MAX_RECORDS)
            prefs[KEY] = json.encodeToString<List<ReceivedAlarm>>(updated)
        }
    }

    suspend fun markDismissed(id: String) {
        context.alarmsDataStore.edit { prefs ->
            val updated = decode(prefs[KEY]).map {
                if (it.id == id) it.copy(dismissedAsFalse = true) else it
            }
            prefs[KEY] = json.encodeToString<List<ReceivedAlarm>>(updated)
        }
    }

    suspend fun clear() {
        context.alarmsDataStore.edit { it.remove(KEY) }
    }

    private fun decode(raw: String?): List<ReceivedAlarm> =
        raw?.let { runCatching { json.decodeFromString<List<ReceivedAlarm>>(it) }.getOrNull() } ?: emptyList()

    companion object {
        private const val MAX_RECORDS = 100
        private val KEY: Preferences.Key<String> = stringPreferencesKey("received_alarms_json")
        private val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
data class ReceivedAlarm(
    val id: String,
    val triggeredAtEpochMs: Long,
    val peakIntensity: Float,
    val sustainedSeconds: Float,
    val recipientsNotified: Int,
    val mapsLink: String?,
    val dismissedAsFalse: Boolean = false,
)
