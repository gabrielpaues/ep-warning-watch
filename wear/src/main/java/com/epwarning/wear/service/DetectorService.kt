package com.epwarning.wear.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.epwarning.shared.AlarmPayload
import com.epwarning.wear.MainActivity
import com.epwarning.wear.R
import com.epwarning.wear.data.AlarmHistoryRepository
import com.epwarning.wear.data.AlarmRecord
import com.epwarning.wear.data.Settings
import com.epwarning.wear.data.SettingsRepository
import com.epwarning.wear.detection.Detection
import com.epwarning.wear.detection.ShakeDetector
import com.epwarning.wear.messaging.PhoneMessenger
import com.epwarning.wear.ui.CountdownActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 *
 * The pre-alarm countdown timer lives here, not in the activity, so the alarm still fires
 * if the visual is killed. The activity is a UX surface that broadcasts a cancel back.
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
    @Volatile private var currentSettings: Settings = Settings(0.5f, 8f, 60f, 5f, false)

    private var countdownJob: Job? = null
    @Volatile private var pendingPayload: AlarmPayload? = null

    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_CANCEL_COUNTDOWN) return
            val payload = pendingPayload ?: return
            val job = countdownJob
            if (job == null || !job.isActive) return
            job.cancel()
            countdownJob = null
            pendingPayload = null
            lifecycleScope.launch {
                alarmRepo.add(
                    AlarmRecord(
                        id = payload.id,
                        triggeredAtEpochMs = payload.triggeredAtEpochMs,
                        peakIntensity = payload.peakIntensity,
                        sustainedSeconds = payload.sustainedSeconds,
                        deliveredToPhone = false,
                        cancelledByUser = true,
                    )
                )
            }
            Log.d(TAG, "countdown cancelled by user")
        }
    }

    private val accelListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val ax = event.values[0]
            val ay = event.values[1]
            val az = event.values[2]
            val linearMag = sqrt(ax * ax + ay * ay + az * az) - SensorManager.GRAVITY_EARTH
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
                currentSettings = settings
                detector.updateConfig(settings.toDetectorConfig())
            }
        }
        registerCancelReceiver()
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
        countdownJob?.cancel()
        runCatching { unregisterReceiver(cancelReceiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun registerCancelReceiver() {
        val filter = IntentFilter(ACTION_CANCEL_COUNTDOWN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(cancelReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(cancelReceiver, filter)
        }
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
        if (countdownJob?.isActive == true) return
        val payload = AlarmPayload(
            id = UUID.randomUUID().toString(),
            triggeredAtEpochMs = System.currentTimeMillis(),
            peakIntensity = trigger.peakIntensity,
            sustainedSeconds = trigger.sustainedSeconds,
        )
        val countdownSeconds = currentSettings.countdownSeconds
        if (countdownSeconds <= 0f) {
            sendAlarmAndRecord(payload)
            return
        }
        startCountdown(payload, countdownSeconds)
    }

    private fun startCountdown(payload: AlarmPayload, totalSeconds: Float) {
        pendingPayload = payload
        val totalMs = (totalSeconds * 1000).toLong()
        val deadlineMs = System.currentTimeMillis() + totalMs

        val activityIntent = Intent(this, CountdownActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(CountdownActivity.EXTRA_DEADLINE_MS, deadlineMs)
        runCatching { startActivity(activityIntent) }

        countdownJob = lifecycleScope.launch {
            try {
                while (true) {
                    val remaining = deadlineMs - System.currentTimeMillis()
                    if (remaining <= 0) break
                    val isFinalSecond = remaining <= 1500L
                    vibrateTick(isFinalSecond)
                    playToneTick(isFinalSecond)
                    delay(remaining.coerceAtMost(1000L))
                }
                pendingPayload = null
                countdownJob = null
                sendAlarmAndRecord(payload)
            } catch (_: CancellationException) {
                // cancel receiver records the cancelled alarm
            }
        }
    }

    private fun vibrator(): Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as? Vibrator
    }

    private fun vibrateTick(isFinalSecond: Boolean) {
        val v = vibrator() ?: return
        val durationMs = if (isFinalSecond) 600L else 180L
        val amplitude = if (isFinalSecond) VibrationEffect.DEFAULT_AMPLITUDE else 80
        runCatching { v.vibrate(VibrationEffect.createOneShot(durationMs, amplitude)) }
    }

    private fun playToneTick(isFinalSecond: Boolean) {
        runCatching {
            val gen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            val tone = if (isFinalSecond) ToneGenerator.TONE_CDMA_HIGH_L else ToneGenerator.TONE_PROP_BEEP
            val durationMs = if (isFinalSecond) 600 else 180
            gen.startTone(tone, durationMs)
            lifecycleScope.launch {
                delay((durationMs + 100).toLong())
                runCatching { gen.release() }
            }
        }
    }

    private fun sendAlarmAndRecord(payload: AlarmPayload) {
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
        const val ACTION_CANCEL_COUNTDOWN = "com.epwarning.wear.CANCEL_COUNTDOWN"

        private const val BATCH_LATENCY_US = 5_000_000
        private const val WAKE_GATE_M_S2 = 2.5f
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
