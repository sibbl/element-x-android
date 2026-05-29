/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.watchbridge.contract

import kotlinx.serialization.Serializable

/**
 * Top-level transport envelope. Every message crossing the watch/phone boundary is wrapped in this.
 *
 * Keep this stable. Breaking changes MUST bump [WatchProtocol.VERSION].
 */
@Serializable
data class WatchSyncEnvelope(
    val protocolVersion: Int = WatchProtocol.VERSION,
    val generatedAtMs: Long,
    val expiresAtMs: Long? = null,
    val applicationId: String = "",
    val payload: WatchPayload,
) {
    fun stampedForApplicationId(applicationId: String): WatchSyncEnvelope {
        return if (this.applicationId == applicationId) this else copy(applicationId = applicationId)
    }

    fun isForApplicationId(applicationId: String): Boolean {
        return this.applicationId.isBlank() || this.applicationId == applicationId
    }
}

/**
 * Sealed root for everything that can travel inside a [WatchSyncEnvelope].
 *
 * Three disjoint hierarchies:
 *   - [WatchSync]: phone -> watch snapshots / deltas / invalidations.
 *   - [WatchCommand]: watch -> phone actions that require a side effect.
 *   - [WatchAck]: phone -> watch result for a command, or a terminal error.
 */
@Serializable
sealed interface WatchPayload
