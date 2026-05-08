package com.epwarning.wear.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.epwarning.wear.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            try {
                val settings = SettingsRepository(app).settings.first()
                if (settings.monitoringEnabled) {
                    DetectorService.start(app)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
