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
import java.util.UUID

private val Context.contactsDataStore by preferencesDataStore(name = "ep_warning_contacts")

class ContactsRepository(private val context: Context) {

    val contacts: Flow<List<Contact>> = context.contactsDataStore.data.map { prefs ->
        val raw = prefs[KEY] ?: return@map emptyList()
        runCatching { json.decodeFromString<List<Contact>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun add(label: String, phoneNumber: String) {
        context.contactsDataStore.edit { prefs ->
            val existing = decode(prefs[KEY])
            val updated = existing + Contact(UUID.randomUUID().toString(), label, phoneNumber)
            prefs[KEY] = json.encodeToString<List<Contact>>(updated)
        }
    }

    suspend fun remove(id: String) {
        context.contactsDataStore.edit { prefs ->
            val updated = decode(prefs[KEY]).filterNot { it.id == id }
            prefs[KEY] = json.encodeToString<List<Contact>>(updated)
        }
    }

    private fun decode(raw: String?): List<Contact> =
        raw?.let { runCatching { json.decodeFromString<List<Contact>>(it) }.getOrNull() } ?: emptyList()

    companion object {
        private val KEY: Preferences.Key<String> = stringPreferencesKey("contacts_json")
        private val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
data class Contact(val id: String, val label: String, val phoneNumber: String)
