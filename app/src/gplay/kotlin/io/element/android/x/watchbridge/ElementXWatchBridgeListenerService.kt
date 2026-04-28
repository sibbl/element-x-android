/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.watchbridge

import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import io.element.android.watchbridge.WatchBridgeDispatcher
import io.element.android.watchbridge.WatchBridgeListenerService
import io.element.android.watchbridge.contract.WatchDataPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import timber.log.Timber

/**
 * GPlay-only Wear Data Layer listener that wires incoming watch commands to the active Element X session.
 */
class ElementXWatchBridgeListenerService : WatchBridgeListenerService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        ElementXWatchBridgeRuntime.start(applicationContext)
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WatchDataPaths.COMMAND) return
        Timber.d("WatchBridge listener received path=%s bytes=%d", event.path, event.data.size)
        val envelope = runCatching { WatchBridgeDispatcher.parse(event.data) }
            .onFailure { Timber.w(it, "Failed to parse watch envelope") }
            .getOrNull() ?: return
        ElementXWatchBridgeRuntime.dispatch(applicationContext, envelope)
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        val draftId = WatchDataPaths.voiceDraftId(channel.path)
        val channelClient = Wearable.getChannelClient(this)
        serviceScope.launch {
            try {
                if (draftId == null) {
                    Timber.d("Ignoring non-voice channel path=%s", channel.path)
                    return@launch
                }
                val audioBytes = channelClient.getInputStream(channel).await().use { input -> input.readBytes() }
                Timber.d("WatchBridge listener received voice draft=%s bytes=%d", draftId, audioBytes.size)
                ElementXWatchBridgeRuntime.dispatchVoiceDraftAudio(applicationContext, draftId, audioBytes)
            } catch (failure: Throwable) {
                Timber.w(failure, "Failed to receive watch voice draft path=%s", channel.path)
            } finally {
                runCatching { channelClient.close(channel).await() }
                    .onFailure { Timber.w(it, "Failed to close watch voice channel path=%s", channel.path) }
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
