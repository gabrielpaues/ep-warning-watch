package com.epwarning.wear.detection

import kotlin.math.sqrt

/**
 * Detects sustained shaking characteristic of a tonic-clonic seizure episode.
 *
 * Approach: maintain a rolling window of gyroscope angular-speed magnitudes.
 * When the window's mean exceeds a sensitivity-derived threshold continuously
 * for [DetectorConfig.sustainSeconds], emit a [Detection.Trigger]. Brief jolts
 * (typing, hand wave, reaching) fail the sustain requirement and are rejected.
 *
 * Pure Kotlin — no Android deps — so it is unit-testable.
 */
class ShakeDetector(private var config: DetectorConfig = DetectorConfig.DEFAULT) {

    private val window = ArrayDeque<Sample>()
    private var sustainStartNs: Long = 0L
    private var lastTriggerNs: Long = 0L
    private var peakIntensity: Float = 0f

    fun updateConfig(newConfig: DetectorConfig) {
        config = newConfig
    }

    fun reset() {
        window.clear()
        sustainStartNs = 0L
        peakIntensity = 0f
    }

    /** Feed a gyroscope sample. Returns a [Detection] when state changes. */
    fun onGyroSample(timestampNs: Long, x: Float, y: Float, z: Float): Detection {
        val magnitude = sqrt(x * x + y * y + z * z)
        window.addLast(Sample(timestampNs, magnitude))
        // Drop samples older than the analysis window.
        val windowNs = (config.windowSeconds * 1e9f).toLong()
        while (window.isNotEmpty() && timestampNs - window.first().tNs > windowNs) {
            window.removeFirst()
        }
        if (window.size < MIN_SAMPLES) return Detection.Idle

        val mean = window.sumOf { it.magnitude.toDouble() }.toFloat() / window.size
        val threshold = config.thresholdRadPerSec()
        val cooldownNs = (config.cooldownSeconds * 1e9f).toLong()
        val sustainNs = (config.sustainSeconds * 1e9f).toLong()

        return if (mean >= threshold) {
            if (sustainStartNs == 0L) sustainStartNs = timestampNs
            if (mean > peakIntensity) peakIntensity = mean
            val sustainedFor = timestampNs - sustainStartNs
            if (sustainedFor >= sustainNs && timestampNs - lastTriggerNs >= cooldownNs) {
                lastTriggerNs = timestampNs
                val event = Detection.Trigger(
                    peakIntensity = peakIntensity,
                    sustainedSeconds = sustainedFor / 1e9f,
                )
                sustainStartNs = 0L
                peakIntensity = 0f
                event
            } else {
                Detection.Building(progress = (sustainedFor.toFloat() / sustainNs).coerceIn(0f, 1f))
            }
        } else {
            sustainStartNs = 0L
            peakIntensity = 0f
            Detection.Idle
        }
    }

    private data class Sample(val tNs: Long, val magnitude: Float)

    companion object {
        private const val MIN_SAMPLES = 8
    }
}

sealed interface Detection {
    data object Idle : Detection
    data class Building(val progress: Float) : Detection
    data class Trigger(val peakIntensity: Float, val sustainedSeconds: Float) : Detection
}

/**
 * @param sensitivity 0..1 — higher means more sensitive (lower threshold).
 * @param sustainSeconds how long shaking must persist to trigger.
 * @param windowSeconds rolling-window length for the running mean.
 * @param cooldownSeconds suppress repeat triggers for this long after firing.
 */
data class DetectorConfig(
    val sensitivity: Float,
    val sustainSeconds: Float,
    val windowSeconds: Float,
    val cooldownSeconds: Float,
) {
    /**
     * Map sensitivity (0..1) to a gyro-magnitude threshold in rad/s.
     * At sensitivity 0 → ~12 rad/s (only very vigorous shaking).
     * At sensitivity 1 → ~3 rad/s (light but sustained motion).
     * For reference: a casual wrist flick peaks around 4–6 rad/s but is not sustained.
     */
    fun thresholdRadPerSec(): Float = MAX_THRESHOLD - sensitivity * (MAX_THRESHOLD - MIN_THRESHOLD)

    companion object {
        private const val MAX_THRESHOLD = 12f
        private const val MIN_THRESHOLD = 3f

        val DEFAULT = DetectorConfig(
            sensitivity = 0.5f,
            sustainSeconds = 8f,
            windowSeconds = 2f,
            cooldownSeconds = 60f,
        )
    }
}
