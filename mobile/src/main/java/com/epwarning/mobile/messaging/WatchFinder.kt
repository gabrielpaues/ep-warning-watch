package com.epwarning.mobile.messaging

import android.content.Context
import com.epwarning.shared.DataLayerProtocol
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class WatchFinder(private val context: Context) {

    private val capabilityClient: CapabilityClient = Wearable.getCapabilityClient(context)

    suspend fun pairedWatchNodeIds(): Set<String> = withContext(Dispatchers.IO) {
        runCatching {
            capabilityClient
                .getCapability(DataLayerProtocol.CAPABILITY_WATCH_DETECTOR, CapabilityClient.FILTER_REACHABLE)
                .await()
                .nodes
                .mapTo(mutableSetOf()) { it.id }
        }.getOrDefault(emptySet())
    }

    fun watchState(): Flow<WatchState> = flow {
        while (true) {
            val nodes = pairedWatchNodeIds()
            emit(if (nodes.isEmpty()) WatchState.NotFound else WatchState.Connected(nodes.size))
            delay(5_000)
        }
    }
}

sealed interface WatchState {
    data object NotFound : WatchState
    data class Connected(val count: Int) : WatchState
}
