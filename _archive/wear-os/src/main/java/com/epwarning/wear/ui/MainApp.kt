package com.epwarning.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.wear.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.InlineSlider
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.epwarning.wear.R
import com.epwarning.wear.data.AlarmRecord
import com.epwarning.wear.messaging.ConnectionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.res.stringResource

@Composable
fun MainApp() {
    val nav = rememberSwipeDismissableNavController()
    MaterialTheme {
        Scaffold(timeText = { TimeText() }) {
            SwipeDismissableNavHost(navController = nav, startDestination = "status") {
                composable("status") { StatusScreen(onSettings = { nav.navigate("settings") }, onHistory = { nav.navigate("history") }) }
                composable("settings") { SettingsScreen() }
                composable("history") { HistoryScreen() }
            }
        }
    }
}

@Composable
private fun StatusScreen(onSettings: () -> Unit, onHistory: () -> Unit) {
    val vm: MainViewModel = viewModel()
    val settings by vm.settings.collectAsState()
    val connection by vm.connection.collectAsState()
    val state = rememberScalingLazyListState()
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = state,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { Text("EP Warning", style = MaterialTheme.typography.title3) }
        item {
            val label = when (val c = connection) {
                ConnectionState.Disconnected -> stringResource(R.string.phone_disconnected)
                is ConnectionState.Connected -> stringResource(R.string.phone_connected)
            }
            Chip(
                label = { Text(label) },
                onClick = {},
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            if (settings.monitoringEnabled) {
                Button(onClick = { vm.stopMonitoring() }) { Text(stringResource(R.string.stop_monitoring)) }
            } else {
                Button(onClick = { vm.startMonitoring() }) { Text(stringResource(R.string.start_monitoring)) }
            }
        }
        item {
            Chip(
                label = { Text(stringResource(R.string.screen_settings)) },
                onClick = onSettings,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Chip(
                label = { Text(stringResource(R.string.screen_history)) },
                onClick = onHistory,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SettingsScreen() {
    val vm: MainViewModel = viewModel()
    val settings by vm.settings.collectAsState()
    Column(
        Modifier.fillMaxSize().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.sensitivity))
        InlineSlider(
            value = settings.sensitivity,
            onValueChange = { vm.setSensitivity(it) },
            valueRange = 0f..1f,
            steps = 9,
            increaseIcon = { Text("+") },
            decreaseIcon = { Text("-") },
        )
        Text("${(settings.sensitivity * 100).toInt()}%")
        Text(stringResource(R.string.sustain))
        InlineSlider(
            value = settings.sustainSeconds,
            onValueChange = { vm.setSustain(it) },
            valueRange = 4f..20f,
            steps = 7,
            increaseIcon = { Text("+") },
            decreaseIcon = { Text("-") },
        )
        Text("${settings.sustainSeconds.toInt()}s")
    }
}

@Composable
private fun HistoryScreen() {
    val vm: MainViewModel = viewModel()
    val alarms by vm.alarms.collectAsState()
    if (alarms.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.no_alarms))
        }
        return
    }
    ScalingLazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(alarms) { record -> AlarmRow(record) }
        item {
            Chip(
                label = { Text(stringResource(R.string.clear_history)) },
                onClick = { vm.clearHistory() },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private val timeFormat = SimpleDateFormat("MMM d HH:mm", Locale.getDefault())

@Composable
private fun AlarmRow(record: AlarmRecord) {
    val time = timeFormat.format(Date(record.triggeredAtEpochMs))
    val status = if (record.deliveredToPhone) "✓" else "!"
    Chip(
        label = { Text("$status $time") },
        secondaryLabel = { Text("${"%.1f".format(record.peakIntensity)} rad/s, ${record.sustainedSeconds.toInt()}s") },
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
    )
}
