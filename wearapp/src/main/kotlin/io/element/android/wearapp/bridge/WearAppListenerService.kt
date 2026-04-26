/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.bridge

import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import io.element.android.wearapp.WearApp
import timber.log.Timber

/**
 * Manifest-declared listener that wakes the watch process on Data Layer events and forwards them
 * to the singleton [WearBridgeClient].
 */
class WearAppListenerService : WearableListenerService() {

    private val client get() = (application as WearApp).bridgeClient

    override fun onDataChanged(events: DataEventBuffer) {
        Timber.d("WearAppListenerService onDataChanged count=%d", events.count)
        client.onDataChanged(events)
    }

    override fun onMessageReceived(event: MessageEvent) {
        Timber.d("WearAppListenerService onMessageReceived path=%s bytes=%d", event.path, event.data.size)
        client.onMessageReceived(event.path, event.data)
    }
}
