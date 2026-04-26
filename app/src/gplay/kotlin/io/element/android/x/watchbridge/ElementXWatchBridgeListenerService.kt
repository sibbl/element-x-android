/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.watchbridge

import com.google.android.gms.wearable.MessageEvent
import io.element.android.watchbridge.WatchBridgeDispatcher
import io.element.android.watchbridge.WatchBridgeListenerService
import io.element.android.watchbridge.contract.WatchDataPaths
import timber.log.Timber

/**
 * GPlay-only Wear Data Layer listener that wires incoming watch commands to the active Element X session.
 */
class ElementXWatchBridgeListenerService : WatchBridgeListenerService() {
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
}