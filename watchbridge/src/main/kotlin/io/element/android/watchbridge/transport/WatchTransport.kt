/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.watchbridge.transport

import io.element.android.watchbridge.contract.WatchSyncEnvelope

/**
 * Abstraction over the Wear OS Data Layer used by the bridge.
 *
 * Implemented by [PlayServicesWatchTransport] in production, or by an in-memory fake in
 * `:watchbridge-testing` for unit / contract tests.
 */
interface WatchTransport {
    /** Emit a snapshot / delta to the watch via `DataClient`. */
    suspend fun publishSync(path: String, envelope: WatchSyncEnvelope)

    /** Send a one-shot envelope to the watch via `MessageClient`. Returns the phone node id used. */
    suspend fun sendMessage(path: String, envelope: WatchSyncEnvelope): String

    /** Delete a previously published sync item. */
    suspend fun deleteSync(path: String)

    /** Open a bidirectional byte channel (for voice bytes). Returns null if no node is reachable. */
    suspend fun openChannel(path: String): WatchChannel?
}

interface WatchChannel : AutoCloseable {
    suspend fun writeAll(bytes: ByteArray)
    suspend fun readAll(): ByteArray
}
