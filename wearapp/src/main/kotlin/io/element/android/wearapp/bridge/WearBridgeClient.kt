/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.bridge

import android.content.Context
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import io.element.android.watchbridge.contract.WatchAck
import io.element.android.watchbridge.contract.WatchBridgeSerialization
import io.element.android.watchbridge.contract.WatchCommand
import io.element.android.watchbridge.contract.WatchDataPaths
import io.element.android.watchbridge.contract.WatchPayload
import io.element.android.watchbridge.contract.WatchProtocol
import io.element.android.watchbridge.contract.WatchSync
import io.element.android.watchbridge.contract.WatchSyncEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID

private const val DUPLICATE_CAPABILITY_STATUS_CODE = 4006

/**
 * Watch-side entry point to the companion protocol.
 *
 * Wraps Google Play Services Wearable clients and surfaces
 *   - a [favorites] flow derived from phone-published `/watchbridge/favorites` `DataClient` items,
 *   - a generic [syncEvents] shared flow for per-room / per-thread subscriptions,
 *   - an [acks] shared flow so send UIs can observe their own request ids,
 *   - [send] to issue [WatchCommand]s to the phone.
 *
 * The actual Data Layer listener is declared in the manifest and forwards into [onDataChanged] /
 * [onMessageReceived].
 */
class WearBridgeClient(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val dataClient: DataClient by lazy { Wearable.getDataClient(context) }
    private val messageClient: MessageClient by lazy { Wearable.getMessageClient(context) }
    private val capabilityClient: CapabilityClient by lazy { Wearable.getCapabilityClient(context) }
    private val nodeClient: NodeClient by lazy { Wearable.getNodeClient(context) }

    private val _favorites = MutableStateFlow<List<io.element.android.watchbridge.contract.WatchFavoriteRoom>>(emptyList())
    val favorites: StateFlow<List<io.element.android.watchbridge.contract.WatchFavoriteRoom>> = _favorites.asStateFlow()

    private val _syncEvents = MutableSharedFlow<WatchPayload>(extraBufferCapacity = 64)
    val syncEvents = _syncEvents.asSharedFlow()

    private val _acks = MutableSharedFlow<WatchAck>(extraBufferCapacity = 64)
    val acks = _acks.asSharedFlow()

    private val _phoneReachable = MutableStateFlow(false)
    val phoneReachable: StateFlow<Boolean> = _phoneReachable.asStateFlow()

    private val phoneCapabilityListener = CapabilityClient.OnCapabilityChangedListener { capabilityInfo ->
        if (capabilityInfo.name == WatchProtocol.PHONE_CAPABILITY) {
            Timber.d(
                "phone capability changed reachable=%s nodes=%s",
                capabilityInfo.nodes.isNotEmpty(),
                capabilityInfo.nodes.joinToString { "${it.displayName}/${it.id}/${it.isNearby}" },
            )
            scope.launch { probePhoneCapability() }
        }
    }

    fun start() {
        capabilityClient.addLocalCapability(WatchProtocol.WATCH_CAPABILITY)
            .addOnSuccessListener { Timber.d("watch capability registered") }
            .addOnFailureListener {
                if (it.isDuplicateCapability()) {
                    Timber.d("watch capability already registered")
                } else {
                    Timber.w(it, "watch capability registration failed")
                }
            }
        capabilityClient.addListener(phoneCapabilityListener, WatchProtocol.PHONE_CAPABILITY)
            .addOnFailureListener { Timber.w(it, "phone capability listener registration failed") }
        scope.launch { probePhoneCapability() }
        scope.launch { primeFavoritesSnapshot() }
    }

    fun stop() {
        capabilityClient.removeListener(phoneCapabilityListener)
        // Scope teardown left to Application lifecycle; explicit cancel intentionally avoided
        // here so in-flight request UIs keep observing acks on Activity restarts.
    }

    fun refreshPhoneReachability() {
        scope.launch { probePhoneCapability() }
    }

    /** Called from the app-level `WearableListenerService` on any `DataItem` change. */
    fun onDataChanged(events: DataEventBuffer) {
        for (ev in events) {
            val item = ev.dataItem ?: continue
            val data = DataMapItem.fromDataItem(item).dataMap.getByteArray("envelope") ?: continue
            val envelope = decode(data) ?: continue
            dispatchIncoming(envelope)
        }
    }

    /** Called from the app-level `WearableListenerService` on an incoming `MessageClient` event. */
    fun onMessageReceived(path: String, bytes: ByteArray) {
        val envelope = decode(bytes) ?: return
        dispatchIncoming(envelope)
    }

    /** Send a command to the phone. Returns the `requestId` so callers can observe their own ack. */
    suspend fun send(builder: (String) -> WatchCommand): String {
        val requestId = UUID.randomUUID().toString()
        val cmd = builder(requestId)
        val envelope = WatchSyncEnvelope(generatedAtMs = System.currentTimeMillis(), payload = cmd)
        val bytes = WatchBridgeSerialization.encodeEnvelopeToBytes(envelope)
        withContext(Dispatchers.IO) {
            val node = resolvePhoneNode() ?: error("phone not reachable")
            messageClient.sendMessage(node.id, WatchDataPaths.COMMAND, bytes).await()
        }
        return requestId
    }

    private suspend fun primeFavoritesSnapshot() = withContext(Dispatchers.IO) {
        runCatching {
            val items = dataClient.getDataItems(android.net.Uri.parse("wear:${WatchDataPaths.FAVORITES}")).await()
            for (index in 0 until items.count) {
                val item = items[index]
                val bytes = DataMapItem.fromDataItem(item).dataMap.getByteArray("envelope") ?: continue
                decode(bytes)?.let(::dispatchIncoming)
            }
            items.release()
        }.onFailure { Timber.w(it, "prime favorites failed") }
    }

    private suspend fun probePhoneCapability() = withContext(Dispatchers.IO) {
        runCatching {
            val caps = capabilityClient
                .getCapability(WatchProtocol.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .await()
            val connectedNodes = nodeClient.connectedNodes.await()
            val nodes = caps.nodes.takeIf { it.isNotEmpty() } ?: connectedNodes
            Timber.d(
                "phone reachability probe reachable=%s capabilityNodes=%s connectedNodes=%s",
                nodes.isNotEmpty(),
                caps.nodes.joinToString { it.debugLabel() },
                connectedNodes.joinToString { it.debugLabel() },
            )
            _phoneReachable.value = nodes.isNotEmpty()
        }.onFailure {
            _phoneReachable.value = false
            Timber.w(it, "phone capability probe failed")
        }
    }

    private suspend fun resolvePhoneNode(): Node? {
        val capabilityNodes = capabilityClient
            .getCapability(WatchProtocol.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .await()
            .nodes
        val nodes = capabilityNodes.takeIf { it.isNotEmpty() } ?: nodeClient.connectedNodes.await()
        return nodes.nearbyFirst()
    }

    private fun Iterable<Node>.nearbyFirst(): Node? = firstOrNull { it.isNearby } ?: firstOrNull()

    private fun Node.debugLabel(): String = "$displayName/$id/$isNearby"

    private fun Throwable.isDuplicateCapability(): Boolean =
        this is ApiException && statusCode == DUPLICATE_CAPABILITY_STATUS_CODE

    private fun dispatchIncoming(envelope: WatchSyncEnvelope) {
        if (envelope.protocolVersion < WatchProtocol.MIN_SUPPORTED_VERSION) {
            Timber.w("dropping unsupported envelope v=%d", envelope.protocolVersion)
            return
        }
        when (val p = envelope.payload) {
            is WatchSync.FavoritesSnapshot -> {
                Timber.d("received favorites snapshot count=%d", p.rooms.size)
                _favorites.value = p.rooms
            }
            is WatchAck -> {
                Timber.d("received ack=%s requestId=%s", p::class.simpleName, p.requestId)
                scope.launch { _acks.emit(p) }
            }
            else -> scope.launch { _syncEvents.emit(p) }
        }
    }

    private fun decode(bytes: ByteArray): WatchSyncEnvelope? = runCatching {
        WatchBridgeSerialization.decodeEnvelopeFromBytes(bytes)
    }.onFailure { Timber.w(it, "envelope decode failed") }.getOrNull()
}
