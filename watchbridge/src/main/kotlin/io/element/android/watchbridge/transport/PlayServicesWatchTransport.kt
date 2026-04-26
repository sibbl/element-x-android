/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.watchbridge.transport

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import io.element.android.watchbridge.contract.WatchBridgeSerialization
import io.element.android.watchbridge.contract.WatchProtocol
import io.element.android.watchbridge.contract.WatchSyncEnvelope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Google Play Services-backed implementation of [WatchTransport]. Used on the phone when the
 * host app opts in to the companion bridge.
 *
 * Small snapshots travel via [DataClient] (persistent, keyed by path).
 * One-shot messages / commands travel via [MessageClient].
 * Voice audio travels via [com.google.android.gms.wearable.ChannelClient] (not wrapped here; see
 * [VoiceChannelBridge]).
 */
class PlayServicesWatchTransport(
    private val context: Context,
) : WatchTransport {

    private val dataClient: DataClient by lazy { Wearable.getDataClient(context) }
    private val messageClient: MessageClient by lazy { Wearable.getMessageClient(context) }
    private val capabilityClient: CapabilityClient by lazy { Wearable.getCapabilityClient(context) }
    private val nodeClient: NodeClient by lazy { Wearable.getNodeClient(context) }

    override suspend fun publishSync(path: String, envelope: WatchSyncEnvelope) = withContext(Dispatchers.IO) {
        val bytes = WatchBridgeSerialization.encodeEnvelopeToBytes(envelope)
        require(bytes.size <= WatchProtocol.MAX_PAYLOAD_BYTES) { "Payload too large: ${bytes.size}" }
        val request = PutDataMapRequest.create(path).apply {
            dataMap.putByteArray("envelope", bytes)
            dataMap.putLong("ts", envelope.generatedAtMs)
        }.asPutDataRequest().setUrgent()
        dataClient.putDataItem(request).await()
        Unit
    }

    override suspend fun sendMessage(path: String, envelope: WatchSyncEnvelope): String = withContext(Dispatchers.IO) {
        val bytes = WatchBridgeSerialization.encodeEnvelopeToBytes(envelope)
        val capabilityNodes = capabilityClient
            .getCapability(WatchProtocol.WATCH_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .await()
            .nodes
        val node = (capabilityNodes.takeIf { it.isNotEmpty() } ?: nodeClient.connectedNodes.await()).nearbyFirst()
            ?: error("No reachable watch node")
        messageClient.sendMessage(node.id, path, bytes).await()
        node.id
    }

    override suspend fun deleteSync(path: String) = withContext(Dispatchers.IO) {
        dataClient.deleteDataItems(android.net.Uri.parse("wear:$path")).await()
        Unit
    }

    override suspend fun openChannel(path: String): WatchChannel? {
        // Channel handling is non-trivial (must be bound to a ChannelClient callback lifecycle)
        // and is wrapped separately in [VoiceChannelBridge]. We intentionally return null here
        // to force callers to use the dedicated voice pipeline.
        return null
    }

    private fun Iterable<Node>.nearbyFirst(): Node? = firstOrNull { it.isNearby } ?: firstOrNull()
}
