package com.epwarning.mobile.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.epwarning.mobile.R
import com.epwarning.mobile.data.Contact
import com.epwarning.mobile.data.ReceivedAlarm
import com.epwarning.mobile.messaging.WatchState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(onRequestPermissions: () -> Unit) {
    val tabs = listOf(R.string.tab_status, R.string.tab_contacts, R.string.tab_alarms)
    var selected by remember { mutableStateOf(0) }

    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                TabRow(selectedTabIndex = selected) {
                    tabs.forEachIndexed { i, res ->
                        Tab(selected = selected == i, onClick = { selected = i }, text = { Text(stringResource(res)) })
                    }
                }
                when (selected) {
                    0 -> StatusTab(onRequestPermissions)
                    1 -> ContactsTab()
                    2 -> AlarmsTab()
                }
            }
        }
    }
}

@Composable
private fun StatusTab(onRequestPermissions: () -> Unit) {
    val vm: MainViewModel = viewModel()
    val watch by vm.watchState.collectAsState()
    val ctx = LocalContext.current
    val needsPermissions = !hasAllRuntimePermissions(ctx)
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = when (val w = watch) {
                        WatchState.NotFound -> stringResource(R.string.watch_disconnected)
                        is WatchState.Connected -> stringResource(R.string.watch_connected)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        if (needsPermissions) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.permissions_needed))
                    Button(onClick = onRequestPermissions, modifier = Modifier.padding(top = 8.dp)) {
                        Text(stringResource(R.string.grant_permissions))
                    }
                }
            }
        }
    }
}

private fun hasAllRuntimePermissions(ctx: Context): Boolean {
    val needed = listOf(
        Manifest.permission.SEND_SMS,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )
    return needed.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
}

@Composable
private fun ContactsTab() {
    val vm: MainViewModel = viewModel()
    val contacts by vm.contacts.collectAsState()
    var label by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.add_contact), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = label, onValueChange = { label = it },
            label = { Text(stringResource(R.string.hint_label)) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = phone, onValueChange = { phone = it },
            label = { Text(stringResource(R.string.hint_phone)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                if (phone.isNotBlank()) {
                    vm.addContact(label, phone)
                    label = ""; phone = ""
                }
            },
            enabled = phone.isNotBlank(),
        ) { Text(stringResource(R.string.save)) }
        HorizontalDivider()
        if (contacts.isEmpty()) {
            Text(stringResource(R.string.no_contacts))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(contacts, key = { it.id }) { ContactRow(it, onRemove = { vm.removeContact(it.id) }) }
            }
        }
    }
}

@Composable
private fun ContactRow(contact: Contact, onRemove: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                if (contact.label.isNotBlank()) Text(contact.label, style = MaterialTheme.typography.titleSmall)
                Text(contact.phoneNumber, style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedButton(onClick = onRemove) { Text(stringResource(R.string.remove)) }
        }
    }
}

@Composable
private fun AlarmsTab() {
    val vm: MainViewModel = viewModel()
    val alarms by vm.alarms.collectAsState()
    if (alarms.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) { Text(stringResource(R.string.no_alarms)) }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(alarms, key = { it.id }) { AlarmRow(it, onDismiss = { vm.dismissAlarm(it.id) }) }
    }
}

private val alarmTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

@Composable
private fun AlarmRow(alarm: ReceivedAlarm, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(alarmTimeFormat.format(Date(alarm.triggeredAtEpochMs)), style = MaterialTheme.typography.titleSmall)
            val sentLabel = if (alarm.recipientsNotified > 0) {
                stringResource(R.string.alarm_delivered) + " (${alarm.recipientsNotified})"
            } else {
                stringResource(R.string.alarm_failed)
            }
            Text(sentLabel)
            alarm.mapsLink?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(
                "Peak ${"%.1f".format(alarm.peakIntensity)} rad/s · ${alarm.sustainedSeconds.toInt()}s",
                style = MaterialTheme.typography.bodySmall,
            )
            if (alarm.dismissedAsFalse) {
                Text(stringResource(R.string.alarm_dismissed), style = MaterialTheme.typography.labelMedium)
            } else {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.clear_alarm))
                }
            }
        }
    }
}
