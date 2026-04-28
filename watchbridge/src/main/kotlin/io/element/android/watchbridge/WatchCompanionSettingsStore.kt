/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.watchbridge

import android.content.Context
import android.content.SharedPreferences
import io.element.android.watchbridge.contract.WatchCompanionSettings
import io.element.android.watchbridge.contract.WatchLongPressConversationAction
import io.element.android.watchbridge.contract.WatchLongPressMessageAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Simple SharedPreferences-backed store for Wear OS companion settings.
 * The phone app writes; the bridge reads and publishes to the watch.
 */
class WatchCompanionSettingsStore(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("watch_companion_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<WatchCompanionSettings> = _settings.asStateFlow()

    fun update(settings: WatchCompanionSettings) {
        prefs.edit()
            .putString(KEY_LONG_PRESS_MESSAGE, settings.longPressMessageAction.name)
            .putString(KEY_LONG_PRESS_CONVERSATION, settings.longPressConversationAction.name)
            .apply()
        _settings.value = settings
    }

    private fun load(): WatchCompanionSettings {
        val msgAction = prefs.getString(KEY_LONG_PRESS_MESSAGE, null)
            ?.let { runCatching { WatchLongPressMessageAction.valueOf(it) }.getOrNull() }
            ?: WatchLongPressMessageAction.READ_ALOUD
        val convAction = prefs.getString(KEY_LONG_PRESS_CONVERSATION, null)
            ?.let { runCatching { WatchLongPressConversationAction.valueOf(it) }.getOrNull() }
            ?: WatchLongPressConversationAction.READ_LATEST
        return WatchCompanionSettings(
            longPressMessageAction = msgAction,
            longPressConversationAction = convAction,
        )
    }

    companion object {
        private const val KEY_LONG_PRESS_MESSAGE = "long_press_message_action"
        private const val KEY_LONG_PRESS_CONVERSATION = "long_press_conversation_action"
    }
}
