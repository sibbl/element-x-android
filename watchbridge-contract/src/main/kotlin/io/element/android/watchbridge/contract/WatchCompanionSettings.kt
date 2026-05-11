/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.watchbridge.contract

import kotlinx.serialization.Serializable

/** Actions available for long-press on messages in the watch timeline. */
@Serializable
enum class WatchLongPressMessageAction {
    READ_ALOUD,
    CREATE_THREAD,
    REPLY_EMOJI,
    REPLY_TEXT,
    REPLY_VOICE,
}

/** Actions available for long-press on conversations in the favorites list. */
@Serializable
enum class WatchLongPressConversationAction {
    READ_LATEST,
    QUICK_REPLY_EMOJI,
    QUICK_REPLY_TEXT,
    QUICK_REPLY_VOICE,
    OPEN_LATEST,
}

/** Primary action when a conversation is tapped from a Wear tile. */
@Serializable
enum class WatchTileConversationAction {
    OPEN_CONVERSATION,
    READ_LATEST,
    QUICK_REPLY_EMOJI,
    QUICK_REPLY_TEXT,
    QUICK_REPLY_VOICE,
    OPEN_LATEST,

    /** Kept so watches can still decode settings written by older phone builds. */
    @Deprecated("Use QUICK_REPLY_TEXT")
    DIRECT_REPLY,

    /** Kept so watches can still decode settings written by older phone builds. */
    @Deprecated("Use QUICK_REPLY_VOICE")
    VOICE_RECORDING,
}

/** Built-in vibration profiles available for watch-local notifications. */
@Serializable
enum class WatchNotificationVibrationPattern {
    SILENT,
    DEFAULT,
    DOUBLE,
    LONG,
    TRIPLE,
    PULSE,
    ESCALATING,
    CUSTOM,
}

/** Room-specific vibration override that wins over the category default. */
@Serializable
data class WatchConversationVibrationOverride(
    val roomId: String,
    val pattern: WatchNotificationVibrationPattern? = null,
    val customPattern: String = "",
)

/** Notification vibration settings grouped by conversation type. */
@Serializable
data class WatchNotificationVibrationSettings(
    val groups: WatchNotificationVibrationPattern = WatchNotificationVibrationPattern.DEFAULT,
    val groupsCustomPattern: String = "",
    val dms: WatchNotificationVibrationPattern = WatchNotificationVibrationPattern.DEFAULT,
    val dmsCustomPattern: String = "",
    val favoriteGroups: WatchNotificationVibrationPattern = WatchNotificationVibrationPattern.DEFAULT,
    val favoriteGroupsCustomPattern: String = "",
    val favoriteDms: WatchNotificationVibrationPattern = WatchNotificationVibrationPattern.DEFAULT,
    val favoriteDmsCustomPattern: String = "",
    val conversationOverrides: List<WatchConversationVibrationOverride> = emptyList(),
)

/** Settings controlling watch companion behavior, configured from the phone app. */
@Serializable
data class WatchCompanionSettings(
    val longPressMessageAction: WatchLongPressMessageAction = WatchLongPressMessageAction.READ_ALOUD,
    val longPressConversationAction: WatchLongPressConversationAction = WatchLongPressConversationAction.READ_LATEST,
    val recentConversationsTileAction: WatchTileConversationAction = WatchTileConversationAction.OPEN_CONVERSATION,
    val favoriteConversationsTileAction: WatchTileConversationAction = WatchTileConversationAction.OPEN_CONVERSATION,
    val notificationVibrations: WatchNotificationVibrationSettings = WatchNotificationVibrationSettings(),
)
