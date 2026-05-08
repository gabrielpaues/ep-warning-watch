package com.epwarning.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.epwarning.mobile.data.Contact
import com.epwarning.mobile.data.ContactsRepository
import com.epwarning.mobile.data.ReceivedAlarm
import com.epwarning.mobile.data.ReceivedAlarmsRepository
import com.epwarning.mobile.messaging.WatchFinder
import com.epwarning.mobile.messaging.WatchState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val contactsRepo = ContactsRepository(app)
    private val alarmsRepo = ReceivedAlarmsRepository(app)
    private val watchFinder = WatchFinder(app)

    val contacts: StateFlow<List<Contact>> = contactsRepo.contacts.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList(),
    )
    val alarms: StateFlow<List<ReceivedAlarm>> = alarmsRepo.alarms.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList(),
    )
    val watchState: StateFlow<WatchState> = watchFinder.watchState().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), WatchState.NotFound,
    )

    fun addContact(label: String, phone: String) {
        viewModelScope.launch { contactsRepo.add(label.trim(), phone.trim()) }
    }
    fun removeContact(id: String) {
        viewModelScope.launch { contactsRepo.remove(id) }
    }
    fun dismissAlarm(id: String) {
        viewModelScope.launch { alarmsRepo.markDismissed(id) }
    }
    fun clearAlarms() {
        viewModelScope.launch { alarmsRepo.clear() }
    }
}
