package com.epwarning.wear.messaging

import android.content.Context
import com.epwarning.shared.AlarmPayload
import com.epwarning.shared.DataLayerProtocol
import com.epwarning.shared.encodeToBytes
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Sends alarms from the watch to any connected phone running the receiver app.
 * Discovers the phone via the Wearable Capability API rather than asking the
 * user to pair manually — the system pairing in Galaxy Wearable is already in place.
 */
class PhoneMessenger(private val context: Context) {

    private val messageClient: MessageClient = Wearable.getMessageClient(context)
    private val capabilityClient: CapabilityClient = Wearable.getCapabilityClient(context)

    suspend fun connectedPhoneNodeIds(): Set<String> = withContext(Dispatchers.IO) {
        runCatching {
            capabilityClient
                .getCapability(DataLayerProtocol.CAPABILITY_PHONE_RECEIVER, CapabilityClient.FILTER_REACHABLE)
                .await()
                .nodes
                .mapTo(mutableSetOf()) { it.id }
        }.getOrDefault(emptySet())
    }

    /** Returns true if at least one phone acknowledged the alarm. */
    suspend fun sendAlarm(payload: AlarmPayload): Boolean = withContext(Dispatchers.IO) {
        val nodes = connectedPhoneNodeIds()
        if (nodes.isEmpty()) return@withContext false
        val bytes = payload.encodeToBytes()
        val results = nodes.map { node ->
            runCatching {
                messageClient.sendMessage(node, DataLayerProtocol.PATH_ALARM, bytes).await()
            }
        }
        results.any { it.isSuccess }
    }

    fun connectionState(): Flow<ConnectionState> = flow {
        while (true) {
            val nodes = connectedPhoneNodeIds()
            emit(if (nodes.isEmpty()) ConnectionState.Disconnected else ConnectionState.Connected(nodes.size))
            kotlinx.coroutines.delay(5_000)
        }
    }
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connected(val phones: Int) : ConnectionState
}
