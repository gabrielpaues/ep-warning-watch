package com.epwarning.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AlarmPayload(
    val id: String,
    val triggeredAtEpochMs: Long,
    val peakIntensity: Float,
    val sustainedSeconds: Float,
)

private val json = Json { ignoreUnknownKeys = true }

fun AlarmPayload.encodeToBytes(): ByteArray = json.encodeToString(AlarmPayload.serializer(), this).toByteArray()

fun ByteArray.decodeAlarmPayload(): AlarmPayload = json.decodeFromString(AlarmPayload.serializer(), String(this))
