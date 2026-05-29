/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.watchbridge.contract

/**
 * Companion protocol version.
 *
 * Incremented on every breaking change to DTOs or the envelope. Parsers MUST read
 * [WatchSyncEnvelope.protocolVersion] first and reject / degrade gracefully if the
 * received version is unsupported.
 */
object WatchProtocol {
    const val VERSION: Int = 1

    /** Minimum version this implementation can still parse (read-only degraded mode). */
    const val MIN_SUPPORTED_VERSION: Int = 1

    /** Wear OS Data Layer path prefix for all companion payloads. */
    const val DATA_PATH_PREFIX: String = "/watchbridge"

    /** Wear OS capability name that the phone app advertises when the bridge is available. */
    const val PHONE_CAPABILITY: String = "element_x_watchbridge_phone"

    /** Wear OS capability name that the watch app advertises. */
    const val WATCH_CAPABILITY: String = "element_x_watchbridge_watch"

    /**
     * Variant-specific phone capability. Debug and release use different application ids, so this
     * keeps a release phone from resolving a debug watch app when both variants are installed.
     */
    fun phoneCapability(applicationId: String): String = variantCapability(PHONE_CAPABILITY, applicationId)

    /**
     * Variant-specific watch capability. Debug and release use different application ids, so this
     * keeps a release watch from resolving a debug phone app when both variants are installed.
     */
    fun watchCapability(applicationId: String): String = variantCapability(WATCH_CAPABILITY, applicationId)

    /** Default command timeout, in milliseconds. */
    const val DEFAULT_COMMAND_TIMEOUT_MS: Long = 15_000L

    /** Hard payload size ceiling that Wear Data Layer can reasonably carry. */
    const val MAX_PAYLOAD_BYTES: Int = 95 * 1024

    private fun variantCapability(base: String, applicationId: String): String {
        val safeApplicationId = applicationId.map { char ->
            if (char.isLetterOrDigit()) char else '_'
        }.joinToString(separator = "")
        return "${base}_$safeApplicationId"
    }
}
