/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.watchbridge

/**
 * Build-time feature flag for everything added by the Wear OS companion fork.
 *
 * Flip to `true` to enable the listener service registration (done in `:app`'s manifest via
 * `tools:node="merge"`). Runtime behavior additionally depends on
 * [io.element.android.watchbridge.settings.WatchBridgePreferences].
 */
object WatchBridgeFeatureFlag {
    const val ENABLED: Boolean = true
}
