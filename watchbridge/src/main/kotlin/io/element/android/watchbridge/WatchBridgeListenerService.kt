/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.watchbridge

import android.content.Intent
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import io.element.android.watchbridge.contract.WatchDataPaths
import timber.log.Timber

/**
 * Phone-side `WearableListenerService` that wakes the app process when a watch command arrives,
 * even if the app is not in the foreground. The Android system guarantees brief execution here.
 *
 * **Integration note:** the host app (`:app`) registers this service in its manifest behind the
 * [WatchBridgeFeatureFlag]. The service delegates actual work to the app-scoped
 * [WatchBridgeDispatcher] via [WatchBridgeEntryPoint] (implemented in the host app with Metro DI).
 */
open class WatchBridgeListenerService : WearableListenerService() {

    /**
     * Host apps override this to fetch the app-scoped [WatchBridgeDispatcher] from their DI graph.
     * Kept abstract-style (open default no-op) so this module stays DI-framework-agnostic.
     */
    protected open fun resolveDispatcher(): WatchBridgeDispatcher? = null

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WatchDataPaths.COMMAND) return
        val dispatcher = resolveDispatcher() ?: run {
            Timber.w("WatchBridge received command but no dispatcher is available")
            return
        }
        val envelope = runCatching { WatchBridgeDispatcher.parse(event.data) }
            .onFailure { Timber.w(it, "Failed to parse watch envelope") }
            .getOrNull() ?: return
        dispatcher.onEnvelope(envelope)
    }

    override fun onCreate() {
        super.onCreate()
        Timber.d("WatchBridgeListenerService started")
    }

    override fun onDestroy() {
        Timber.d("WatchBridgeListenerService stopped")
        super.onDestroy()
    }
}
