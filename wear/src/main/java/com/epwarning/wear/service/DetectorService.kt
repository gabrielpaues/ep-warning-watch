package com.epwarning.wear.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.epwarning.shared.AlarmPayload
import com.epwarning.wear.MainActivity
import com.epwarning.wear.R
import com.epwarning.wear.data.AlarmHistoryRepository
import com.epwarning.wear.data.AlarmRecord
import com.epwarning.wear.data.SettingsRepository
import com.epwarning.wear.detection.Detection
import com.epwarning.wear.detection.ShakeDetector
import com.epwarning.wear.messaging.PhoneMessenger
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.sqrt

/**
 * Foreground service that monitors motion and reports alarms to the phone.
 *
 * Battery strategy:
 *  - Stage 1 (idle): accelerometer at SENSOR_DELAY_NORMAL with a 5s batch. SoC can sleep
 *    between batches; we only wake to scan for motion above a low gate.
 *  - Stage 2 (active): gyroscope at SENSOR_DELAY_GAME feeds ShakeDetector. Drops back to
 *    stage 1 after IDLE_TIMEOUT_NS of below-gate motion.
 *  - No held wakelock; partial wakelocks are acquired only briefly when posting an alarm.
 */
class DetectorService : LifecycleService() {

    private lateinit var sensorManager: SensorManager
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var alarmRepo: AlarmHistoryRepository
    private lateinit var messenger: PhoneMessenger
    private val detector = ShakeDetector()

    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var stage: Stage = Stage.Idle
    private var lastActiveMotionNs: Long = 0L
    private var settingsJob: Job? = null

    private val accelListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val ax = event.values[0]
            val ay = event.values[1]
            val az = event.values[2]
            val linearMag = sqrt(ax * ax + ay * ay + az * az) - SensorManager.GRAVITY_EARTH
            // Cheap motion gate: if linear acceleration exceeds WAKE_GATE, escalate.
            if (kotlin.math.abs(linearMag) > WAKE_GATE_M_S2 && stage == Stage.Idle) {
                escalateToActive()
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val gyroListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val detection = detector.onGyroSample(event.timestamp, event.values[0], event.values[1], event.values[2])
            when (detection) {
                Detection.Idle -> {
                    if (event.timestamp - lastActiveMotionNs > IDLE_TIMEOUT_NS && stage == Stage.Active) {
                        deescalateToIdle()
                    }
                }
                is Detection.Building -> {
                    lastActiveMotionNs = event.timestamp
                }
                is Detection.Trigger -> {
                    lastActiveMotionNs = event.timestamp
                    onAlarmTriggered(detection)
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        settingsRepo = SettingsRepository(applicationContext)
        alarmRepo = AlarmHistoryRepository(applicationContext)
        messenger = PhoneMessenger(applicationContext)

        startInForeground()

        settingsJob = lifecycleScope.launch {
            settingsRepo.settings.distinctUntilChanged().collect { settings ->
                detector.updateConfig(settings.toDetectorConfig())
            }
        }
        registerStageIdle()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(accelListener)
        sensorManager.unregisterListener(gyroListener)
        settingsJob?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun registerStageIdle() {
        sensorManager.unregisterListener(gyroListener)
        accelerometer?.let {
            sensorManager.registerListener(
                accelListener,
                it,
                SensorManager.SENSOR_DELAY_NORMAL,
                BATCH_LATENCY_US,
            )
        }
        stage = Stage.Idle
        Log.d(TAG, "stage=Idle (accel batched)")
    }

    private fun escalateToActive() {
        sensorManager.unregisterListener(accelListener)
        val gyro = gyroscope ?: return registerStageIdle()
        sensorManager.registerListener(gyroListener, gyro, SensorManager.SENSOR_DELAY_GAME, 0)
        detector.reset()
        lastActiveMotionNs = System.nanoTime()
        stage = Stage.Active
        Log.d(TAG, "stage=Active (gyro live)")
    }

    private fun deescalateToIdle() {
        registerStageIdle()
    }

    private fun onAlarmTriggered(trigger: Detection.Trigger) {
        val payload = AlarmPayload(
            id = UUID.randomUUID().toString(),
            triggeredAtEpochMs = System.currentTimeMillis(),
            peakIntensity = trigger.peakIntensity,
            sustainedSeconds = trigger.sustainedSeconds,
        )
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:alarm")
        wl.acquire(5_000)
        lifecycleScope.launch {
            val delivered = runCatching { messenger.sendAlarm(payload) }.getOrDefault(false)
            alarmRepo.add(
                AlarmRecord(
                    id = payload.id,
                    triggeredAtEpochMs = payload.triggeredAtEpochMs,
                    peakIntensity = payload.peakIntensity,
                    sustainedSeconds = payload.sustainedSeconds,
                    deliveredToPhone = delivered,
                )
            )
            postAlarmNotification(delivered)
            if (wl.isHeld) wl.release()
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Monitoring", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = buildOngoingNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildOngoingNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_monitoring_title))
            .setContentText(getString(R.string.notif_monitoring_text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun postAlarmNotification(delivered: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(ALERT_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(ALERT_CHANNEL_ID, "Alerts", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val title = if (delivered) {
            getString(R.string.notif_alarm_sent_title)
        } else {
            getString(R.string.notif_alarm_undelivered_title)
        }
        val n = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(getString(R.string.notif_alarm_text))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID + 1, n)
    }

    private enum class Stage { Idle, Active }

    companion object {
        private const val TAG = "DetectorService"
        private const val CHANNEL_ID = "ep_warning_monitoring"
        private const val ALERT_CHANNEL_ID = "ep_warning_alerts"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "com.epwarning.wear.STOP"

        // Stage-1 batch latency. Larger = better battery, slower escalation.
        private const val BATCH_LATENCY_US = 5_000_000
        // Linear-acceleration gate (m/s^2 over gravity) that wakes stage 2.
        private const val WAKE_GATE_M_S2 = 2.5f
        // Time of below-gate motion before stage 2 drops back to stage 1.
        private const val IDLE_TIMEOUT_NS = 15_000_000_000L

        fun start(context: Context) {
            val intent = Intent(context, DetectorService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, DetectorService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
