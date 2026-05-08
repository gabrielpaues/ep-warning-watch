package com.epwarning.mobile.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat

class SmsSender(private val context: Context) {

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.SEND_SMS,
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * Send the same alarm body to every recipient. Long messages are split into multipart SMS.
     * Returns the count of recipients we successfully handed to the system.
     * SmsManager dispatches asynchronously — "success" here means the OS accepted the request.
     */
    fun sendAlarm(numbers: List<String>, body: String): Int {
        if (!hasPermission() || numbers.isEmpty()) return 0
        val sm = smsManager()
        var sent = 0
        for (number in numbers) {
            val parts = sm.divideMessage(body)
            runCatching {
                if (parts.size > 1) {
                    sm.sendMultipartTextMessage(number, null, parts, null, null)
                } else {
                    sm.sendTextMessage(number, null, body, null, null)
                }
                sent++
            }
        }
        return sent
    }

    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }
    }
}
