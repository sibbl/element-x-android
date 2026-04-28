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

/** Settings controlling watch companion behavior, configured from the phone app. */
@Serializable
data class WatchCompanionSettings(
    val longPressMessageAction: WatchLongPressMessageAction = WatchLongPressMessageAction.READ_ALOUD,
    val longPressConversationAction: WatchLongPressConversationAction = WatchLongPressConversationAction.READ_LATEST,
)
