/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.watchbridge.contract

import kotlinx.serialization.Serializable

/**
 * Compact phone -> watch projection for a single room or thread notification card.
 */
@Serializable
data class WatchNotificationMessagePreview(
    val senderDisplayName: String? = null,
    val bodyText: String,
    val timestampMs: Long,
)

@Serializable
data class WatchMessageNotification(
    val notificationKey: String,
    val roomId: String,
    val eventId: String,
    val threadRootEventId: String? = null,
    val roomDisplayName: String = "",
    val roomKind: WatchRoomKind = WatchRoomKind.GROUP,
    val senderDisplayName: String? = null,
    val bodyText: String? = null,
    val timestampMs: Long,
    val messageCount: Int = 1,
    val previewMessages: List<WatchNotificationMessagePreview> = emptyList(),
    val isNoisy: Boolean = false,
    val imagePreviewBytes: ByteArray? = null,
    val vibrationPatternOverride: WatchNotificationVibrationPattern? = null,
    val customVibrationPattern: String? = null,
    val vibrationSettingsSnapshot: WatchNotificationVibrationSettings? = null,
)
