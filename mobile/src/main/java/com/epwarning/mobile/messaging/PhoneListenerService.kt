package com.epwarning.mobile.messaging

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.epwarning.mobile.MainActivity
import com.epwarning.mobile.R
import com.epwarning.mobile.data.ContactsRepository
import com.epwarning.mobile.data.ReceivedAlarm
import com.epwarning.mobile.data.ReceivedAlarmsRepository
import com.epwarning.mobile.location.LocationProvider
import com.epwarning.mobile.location.mapsLink
import com.epwarning.mobile.sms.SmsSender
import com.epwarning.shared.DataLayerProtocol
import com.epwarning.shared.decodeAlarmPayload
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Receives alarm messages from the watch and turns them into SMS alerts.
 * Runs in its own process via the Wearable extension; we keep it short-lived
 * but kick off a background job for IO so the OS can schedule it.
 */
class PhoneListenerService : WearableListenerService() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != DataLayerProtocol.PATH_ALARM) return
        val payload = runCatching { event.data.decodeAlarmPayload() }.getOrNull() ?: return
        scope.launch { handleAlarm(payload) }
    }

    private suspend fun handleAlarm(payload: com.epwarning.shared.AlarmPayload) {
        val contactsRepo = ContactsRepository(applicationContext)
        val alarmsRepo = ReceivedAlarmsRepository(applicationContext)
        val sms = SmsSender(applicationContext)
        val locator = LocationProvider(applicationContext)

        val contacts = contactsRepo.contacts.first()
        val location = locator.currentLocation()
        val link = location?.let { mapsLink(it.latitude, it.longitude) }
        val body = buildSmsBody(payload.triggeredAtEpochMs, link)

        val recipients = contacts.map { it.phoneNumber }
        val sent = sms.sendAlarm(recipients, body)

        alarmsRepo.add(
            ReceivedAlarm(
                id = payload.id,
                triggeredAtEpochMs = payload.triggeredAtEpochMs,
                peakIntensity = payload.peakIntensity,
                sustainedSeconds = payload.sustainedSeconds,
                recipientsNotified = sent,
                mapsLink = link,
            )
        )
        notifyUser(sent)
    }

    private fun notifyUser(recipientCount: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_alarm_channel), NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_alarm_title))
            .setContentText(getString(R.string.notif_alarm_text, recipientCount))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        nm.notify(NOTIF_ID, n)
    }

    private fun buildSmsBody(epochMs: Long, link: String?): String {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))
        val base = "EP Warning: a possible seizure was detected at $time."
        return if (link != null) "$base Location: $link" else "$base Location unavailable."
    }

    companion object {
        private const val CHANNEL_ID = "ep_warning_alerts"
        private const val NOTIF_ID = 7001
    }
}
