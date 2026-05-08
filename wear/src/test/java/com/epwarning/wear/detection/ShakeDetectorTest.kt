package com.epwarning.wear.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShakeDetectorTest {

    private val sampleIntervalNs = 20_000_000L

    private val testConfig = DetectorConfig(
        sensitivity = 0.5f,
        sustainSeconds = 1f,
        windowSeconds = 0.5f,
        cooldownSeconds = 2f,
    )

    private val startNs = 5_000_000_000L

    private fun feedAt(detector: ShakeDetector, magnitude: Float, count: Int, fromNs: Long = startNs): List<Pair<Long, Detection>> {
        val results = mutableListOf<Pair<Long, Detection>>()
        var t = fromNs
        repeat(count) {
            t += sampleIntervalNs
            results += t to detector.onGyroSample(t, magnitude, 0f, 0f)
        }
        return results
    }

    @Test
    fun returnsIdleWhileWarmingUpBelowMinSamples() {
        val detector = ShakeDetector(testConfig)
        val results = feedAt(detector, magnitude = 20f, count = 7)
        results.forEach { (_, d) -> assertEquals(Detection.Idle, d) }
    }

    @Test
    fun returnsIdleForSustainedLowMagnitude() {
        val detector = ShakeDetector(testConfig)
        val results = feedAt(detector, magnitude = 1f, count = 100)
        assertEquals(Detection.Idle, results.last().second)
        assertTrue(results.none { it.second is Detection.Trigger })
    }

    @Test
    fun returnsBuildingForShortBurstAboveThreshold() {
        val detector = ShakeDetector(testConfig)
        val results = feedAt(detector, magnitude = 10f, count = 20)
        val last = results.last().second
        assertTrue("expected Building, got $last", last is Detection.Building)
        val progress = (last as Detection.Building).progress
        assertTrue("progress should be in (0, 1) before sustain hits, was $progress", progress in 0f..1f)
        assertTrue("progress should be < 1 well before sustain, was $progress", progress < 1f)
    }

    @Test
    fun triggersWhenSustainedAboveThresholdForSustainSeconds() {
        val detector = ShakeDetector(testConfig)
        val results = feedAt(detector, magnitude = 10f, count = 100)
        val trigger = results.firstNotNullOfOrNull { (_, d) -> d as? Detection.Trigger }
        assertNotNull("expected a Trigger somewhere in the stream", trigger)
        assertTrue(
            "peakIntensity should be at or above mean magnitude (~10f)",
            trigger!!.peakIntensity >= testConfig.thresholdRadPerSec(),
        )
        assertTrue(
            "sustainedSeconds should be >= configured sustain (~1f)",
            trigger.sustainedSeconds >= testConfig.sustainSeconds - 0.05f,
        )
    }

    @Test
    fun doesNotRetriggerDuringCooldown() {
        val detector = ShakeDetector(testConfig)
        val first = feedAt(detector, magnitude = 10f, count = 100)
        val firstTrigger = first.firstNotNullOfOrNull { (t, d) -> (d as? Detection.Trigger)?.let { t to it } }
        requireNotNull(firstTrigger) { "first trigger never fired" }

        val secondBatch = feedAt(detector, magnitude = 10f, count = 50, fromNs = first.last().first)
        assertFalse(
            "second Trigger should not fire within cooldown (= ${testConfig.cooldownSeconds}s)",
            secondBatch.any { it.second is Detection.Trigger },
        )
    }

    @Test
    fun briefJoltFollowedByQuietDoesNotTrigger() {
        val detector = ShakeDetector(testConfig)
        val burst = feedAt(detector, magnitude = 15f, count = 10)
        val tail = feedAt(detector, magnitude = 0.5f, count = 200, fromNs = burst.last().first)
        val all = burst + tail
        assertFalse("brief jolt must not trigger", all.any { it.second is Detection.Trigger })
    }

    @Test
    fun resetReturnsToIdleAfterBuildup() {
        val detector = ShakeDetector(testConfig)
        feedAt(detector, magnitude = 10f, count = 20)
        detector.reset()
        val results = feedAt(detector, magnitude = 0.5f, count = 30, fromNs = startNs + 100 * sampleIntervalNs)
        assertEquals(
            "after reset and sustained low-magnitude feed, last detection should be Idle",
            Detection.Idle,
            results.last().second,
        )
    }

    @Test
    fun thresholdMappingHitsTheDocumentedEndpoints() {
        val cfg = { s: Float -> DetectorConfig(s, 1f, 0.5f, 2f) }
        assertEquals(12f, cfg(0f).thresholdRadPerSec(), 0.001f)
        assertEquals(3f, cfg(1f).thresholdRadPerSec(), 0.001f)
        assertEquals(7.5f, cfg(0.5f).thresholdRadPerSec(), 0.001f)
    }

    @Test
    fun updateConfigChangesThresholdMidStream() {
        val detector = ShakeDetector(DetectorConfig(sensitivity = 0f, sustainSeconds = 1f, windowSeconds = 0.5f, cooldownSeconds = 2f))
        val belowHighThreshold = feedAt(detector, magnitude = 8f, count = 100)
        assertTrue(
            "with sensitivity=0 (threshold=12), 8 rad/s should never trigger",
            belowHighThreshold.none { it.second is Detection.Trigger },
        )

        detector.updateConfig(DetectorConfig(sensitivity = 1f, sustainSeconds = 1f, windowSeconds = 0.5f, cooldownSeconds = 2f))
        val aboveLowThreshold = feedAt(detector, magnitude = 8f, count = 200, fromNs = belowHighThreshold.last().first)
        assertTrue(
            "after dropping threshold to 3 rad/s, the same 8 rad/s feed should eventually trigger",
            aboveLowThreshold.any { it.second is Detection.Trigger },
        )
    }
}
