/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.watchbridge.testing

import io.element.android.watchbridge.contract.WatchSyncEnvelope

/**
 * In-memory recorder for phone-side watch transport interactions.
 *
 * Records every sent envelope by path so tests can assert on protocol traffic without touching
 * Play Services.
 */
class WatchTransportRecorder {

    val published: MutableList<Pair<String, WatchSyncEnvelope>> = mutableListOf()
    val messaged: MutableList<Pair<String, WatchSyncEnvelope>> = mutableListOf()
    val deleted: MutableList<String> = mutableListOf()

    suspend fun publishSync(path: String, envelope: WatchSyncEnvelope) {
        published += path to envelope
    }

    suspend fun sendMessage(path: String, envelope: WatchSyncEnvelope): String {
        messaged += path to envelope
        return "fake-node"
    }

    suspend fun deleteSync(path: String) {
        deleted += path
    }
}
