package com.epwarning.wear.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.epwarning.wear.R
import com.epwarning.wear.service.DetectorService
import kotlinx.coroutines.delay
import kotlin.math.ceil

class CountdownActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val deadlineMs = intent.getLongExtra(EXTRA_DEADLINE_MS, System.currentTimeMillis())

        setContent {
            CountdownScreen(
                deadlineMs = deadlineMs,
                onCancel = {
                    val cancel = Intent(DetectorService.ACTION_CANCEL_COUNTDOWN).setPackage(packageName)
                    sendBroadcast(cancel)
                    finish()
                },
                onExpired = { finish() },
            )
        }
    }

    companion object {
        const val EXTRA_DEADLINE_MS = "deadline_ms"
    }
}

@Composable
private fun CountdownScreen(deadlineMs: Long, onCancel: () -> Unit, onExpired: () -> Unit) {
    var remainingSeconds by remember { mutableStateOf(initialRemaining(deadlineMs)) }

    LaunchedEffect(Unit) {
        while (true) {
            val r = initialRemaining(deadlineMs)
            remainingSeconds = r
            if (r <= 0) {
                onExpired()
                break
            }
            delay(50)
        }
    }

    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFB00020)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.countdown_alarm_in),
                    color = Color.White,
                    style = MaterialTheme.typography.title3,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = remainingSeconds.toString(),
                    color = Color.White,
                    style = MaterialTheme.typography.display1,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onCancel) {
                    Text(stringResource(R.string.countdown_cancel))
                }
            }
        }
    }
}

private fun initialRemaining(deadlineMs: Long): Int {
    val ms = deadlineMs - System.currentTimeMillis()
    return ceil(ms / 1000.0).toInt().coerceAtLeast(0)
}
