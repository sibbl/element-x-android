/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.watchbridge

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import io.element.android.watchbridge.contract.WatchCompanionSettings
import io.element.android.watchbridge.contract.WatchConversationVibrationOverride
import io.element.android.watchbridge.contract.WatchLongPressConversationAction
import io.element.android.watchbridge.contract.WatchLongPressMessageAction
import io.element.android.watchbridge.contract.WatchNotificationVibrationPattern
import io.element.android.watchbridge.contract.WatchNotificationVibrationSettings
import io.element.android.watchbridge.contract.WatchTileConversationAction
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
            .putString(KEY_RECENT_TILE_ACTION, settings.recentConversationsTileAction.name)
            .putString(KEY_FAVORITE_TILE_ACTION, settings.favoriteConversationsTileAction.name)
            .putString(KEY_VIBRATION_GROUPS, settings.notificationVibrations.groups.name)
            .putString(KEY_VIBRATION_GROUPS_CUSTOM_PATTERN, settings.notificationVibrations.groupsCustomPattern)
            .putString(KEY_VIBRATION_DMS, settings.notificationVibrations.dms.name)
            .putString(KEY_VIBRATION_DMS_CUSTOM_PATTERN, settings.notificationVibrations.dmsCustomPattern)
            .putString(KEY_VIBRATION_FAVORITE_GROUPS, settings.notificationVibrations.favoriteGroups.name)
            .putString(KEY_VIBRATION_FAVORITE_GROUPS_CUSTOM_PATTERN, settings.notificationVibrations.favoriteGroupsCustomPattern)
            .putString(KEY_VIBRATION_FAVORITE_DMS, settings.notificationVibrations.favoriteDms.name)
            .putString(KEY_VIBRATION_FAVORITE_DMS_CUSTOM_PATTERN, settings.notificationVibrations.favoriteDmsCustomPattern)
            .putString(
                KEY_VIBRATION_CONVERSATION_OVERRIDES,
                encodeConversationOverrides(settings.notificationVibrations.conversationOverrides),
            )
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
        val recentTileAction = readTileAction(KEY_RECENT_TILE_ACTION)
        val favoriteTileAction = readTileAction(KEY_FAVORITE_TILE_ACTION)
        val legacyCustomPattern = prefs.getString(KEY_VIBRATION_CUSTOM_PATTERN, null).orEmpty()
        val groupsPattern = readVibrationPattern(KEY_VIBRATION_GROUPS)
        val dmsPattern = readVibrationPattern(KEY_VIBRATION_DMS)
        val favoriteGroupsPattern = readVibrationPattern(KEY_VIBRATION_FAVORITE_GROUPS)
        val favoriteDmsPattern = readVibrationPattern(KEY_VIBRATION_FAVORITE_DMS)
        val notificationVibrations = WatchNotificationVibrationSettings(
            groups = groupsPattern,
            groupsCustomPattern = readCategoryCustomPattern(KEY_VIBRATION_GROUPS_CUSTOM_PATTERN, groupsPattern, legacyCustomPattern),
            dms = dmsPattern,
            dmsCustomPattern = readCategoryCustomPattern(KEY_VIBRATION_DMS_CUSTOM_PATTERN, dmsPattern, legacyCustomPattern),
            favoriteGroups = favoriteGroupsPattern,
            favoriteGroupsCustomPattern = readCategoryCustomPattern(
                KEY_VIBRATION_FAVORITE_GROUPS_CUSTOM_PATTERN,
                favoriteGroupsPattern,
                legacyCustomPattern,
            ),
            favoriteDms = favoriteDmsPattern,
            favoriteDmsCustomPattern = readCategoryCustomPattern(
                KEY_VIBRATION_FAVORITE_DMS_CUSTOM_PATTERN,
                favoriteDmsPattern,
                legacyCustomPattern,
            ),
            conversationOverrides = decodeConversationOverrides(
                prefs.getString(KEY_VIBRATION_CONVERSATION_OVERRIDES, null),
                legacyCustomPattern = legacyCustomPattern,
            ),
        )
        return WatchCompanionSettings(
            longPressMessageAction = msgAction,
            longPressConversationAction = convAction,
            recentConversationsTileAction = recentTileAction,
            favoriteConversationsTileAction = favoriteTileAction,
            notificationVibrations = notificationVibrations,
        )
    }

    private fun readTileAction(key: String): WatchTileConversationAction {
        return prefs.getString(key, null)
            ?.let { runCatching { WatchTileConversationAction.valueOf(it) }.getOrNull() }
            ?: WatchTileConversationAction.OPEN_CONVERSATION
    }

    private fun readVibrationPattern(key: String): WatchNotificationVibrationPattern {
        return prefs.getString(key, null)
            ?.let { runCatching { WatchNotificationVibrationPattern.valueOf(it) }.getOrNull() }
            ?: WatchNotificationVibrationPattern.DEFAULT
    }

    private fun readCategoryCustomPattern(
        key: String,
        pattern: WatchNotificationVibrationPattern,
        legacyCustomPattern: String,
    ): String {
        if (pattern != WatchNotificationVibrationPattern.CUSTOM) return ""
        return prefs.getString(key, null)
            ?.takeIf { it.isNotEmpty() }
            ?: legacyCustomPattern
    }

    private fun encodeConversationOverrides(overrides: List<WatchConversationVibrationOverride>): String {
        return overrides.joinToString(separator = "|") { override ->
            listOf(
                Uri.encode(override.roomId),
                override.pattern?.name ?: INHERIT_PATTERN_SENTINEL,
                Uri.encode(override.customPattern),
            ).joinToString(separator = ";")
        }
    }

    private fun decodeConversationOverrides(
        encoded: String?,
        legacyCustomPattern: String,
    ): List<WatchConversationVibrationOverride> {
        return encoded
            ?.takeIf { it.isNotBlank() }
            ?.split("|")
            .orEmpty()
            .mapNotNull { token ->
                decodeConversationOverride(token, legacyCustomPattern)
            }
    }

    private fun decodeConversationOverride(
        token: String,
        legacyCustomPattern: String,
    ): WatchConversationVibrationOverride? {
        val modernParts = token.split(';')
        if (modernParts.size >= 2) {
            val roomId = Uri.decode(modernParts[0])
            val pattern = modernParts[1]
                .takeUnless { it == INHERIT_PATTERN_SENTINEL }
                ?.let { patternName -> runCatching { WatchNotificationVibrationPattern.valueOf(patternName) }.getOrNull() }
            val customPattern = modernParts.getOrNull(2)
                ?.let(Uri::decode)
                .orEmpty()
            return WatchConversationVibrationOverride(
                roomId = roomId,
                pattern = pattern,
                customPattern = customPattern,
            )
        }

        val legacySeparatorIndex = token.lastIndexOf(':')
        if (legacySeparatorIndex <= 0 || legacySeparatorIndex >= token.lastIndex) return null
        val roomId = Uri.decode(token.substring(0, legacySeparatorIndex))
        val pattern = runCatching {
            WatchNotificationVibrationPattern.valueOf(token.substring(legacySeparatorIndex + 1))
        }.getOrNull() ?: return null
        return WatchConversationVibrationOverride(
            roomId = roomId,
            pattern = pattern,
            customPattern = legacyCustomPattern.takeIf { pattern == WatchNotificationVibrationPattern.CUSTOM }.orEmpty(),
        )
    }

    companion object {
        private const val INHERIT_PATTERN_SENTINEL = "INHERIT"
        private const val KEY_LONG_PRESS_MESSAGE = "long_press_message_action"
        private const val KEY_LONG_PRESS_CONVERSATION = "long_press_conversation_action"
        private const val KEY_RECENT_TILE_ACTION = "recent_tile_action"
        private const val KEY_FAVORITE_TILE_ACTION = "favorite_tile_action"
        private const val KEY_VIBRATION_GROUPS = "notification_vibration_groups"
        private const val KEY_VIBRATION_GROUPS_CUSTOM_PATTERN = "notification_vibration_groups_custom_pattern"
        private const val KEY_VIBRATION_DMS = "notification_vibration_dms"
        private const val KEY_VIBRATION_DMS_CUSTOM_PATTERN = "notification_vibration_dms_custom_pattern"
        private const val KEY_VIBRATION_FAVORITE_GROUPS = "notification_vibration_favorite_groups"
        private const val KEY_VIBRATION_FAVORITE_GROUPS_CUSTOM_PATTERN =
            "notification_vibration_favorite_groups_custom_pattern"
        private const val KEY_VIBRATION_FAVORITE_DMS = "notification_vibration_favorite_dms"
        private const val KEY_VIBRATION_FAVORITE_DMS_CUSTOM_PATTERN = "notification_vibration_favorite_dms_custom_pattern"
        private const val KEY_VIBRATION_CUSTOM_PATTERN = "notification_vibration_custom_pattern"
        private const val KEY_VIBRATION_CONVERSATION_OVERRIDES = "notification_vibration_conversation_overrides"
    }
}
