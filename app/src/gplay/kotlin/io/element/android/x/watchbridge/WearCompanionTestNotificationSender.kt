/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.watchbridge

import android.content.Context
import io.element.android.watchbridge.contract.WatchCompanionSettings
import io.element.android.watchbridge.contract.WatchDataPaths
import io.element.android.watchbridge.contract.WatchFavoriteRoom
import io.element.android.watchbridge.contract.WatchMessageNotification
import io.element.android.watchbridge.contract.WatchNotificationMessagePreview
import io.element.android.watchbridge.contract.WatchNotificationVibrationPattern
import io.element.android.watchbridge.contract.WatchRoomKind
import io.element.android.watchbridge.contract.WatchSync
import io.element.android.watchbridge.contract.WatchSyncEnvelope
import io.element.android.watchbridge.transport.PlayServicesWatchTransport
import io.element.android.watchbridge.transport.WatchTransport
import io.element.android.x.R
import timber.log.Timber
import java.util.Locale

private const val WEAR_COMPANION_TEST_NOTIFICATION_PATH_PREFIX = "wear-companion-test"
private const val WEAR_COMPANION_TEST_NOTIFICATION_TTL_MS = 45_000L

internal data class WearCompanionTestNotificationSample(
    val roomDisplayName: String,
    val senderDisplayName: String,
    val bodyText: String,
    val messageCount: Int = 1,
    val previewMessages: List<String> = emptyList(),
)

internal class WearCompanionTestNotificationSender(
    private val context: Context,
    private val transport: WatchTransport = PlayServicesWatchTransport(context),
    private val clock: () -> Long = System::currentTimeMillis,
    private val sampleProvider: (WearCompanionVibrationCategory) -> WearCompanionTestNotificationSample = { category ->
        defaultTestNotificationSample(context, category)
    },
) {
    suspend fun send(
        category: WearCompanionVibrationCategory,
        settings: WatchCompanionSettings,
    ) {
        val generatedAtMs = clock()
        val selectedPattern = settings.notificationVibrationFor(category)
        val selectedCustomPattern = if (selectedPattern == WatchNotificationVibrationPattern.CUSTOM) {
            when (category) {
                WearCompanionVibrationCategory.GROUPS -> settings.notificationVibrations.groupsCustomPattern
                WearCompanionVibrationCategory.DMS -> settings.notificationVibrations.dmsCustomPattern
                WearCompanionVibrationCategory.FAVORITE_GROUPS -> settings.notificationVibrations.favoriteGroupsCustomPattern
                WearCompanionVibrationCategory.FAVORITE_DMS -> settings.notificationVibrations.favoriteDmsCustomPattern
            }
        } else null
        val sample = sampleProvider(category)
        publishSampleNotification(
            pathSuffix = category.storageKey(),
            notification = sample.toNotification(
                generatedAtMs = generatedAtMs,
                notificationKeySuffix = category.storageKey(),
                roomId = "!$WEAR_COMPANION_TEST_NOTIFICATION_PATH_PREFIX-${category.storageKey()}:local",
                eventId = "\$$WEAR_COMPANION_TEST_NOTIFICATION_PATH_PREFIX-${category.storageKey()}-$generatedAtMs:local",
                roomDisplayName = sample.roomDisplayName,
                roomKind = category.roomKind(),
                vibrationPatternOverride = selectedPattern,
                customVibrationPattern = selectedCustomPattern,
                vibrationSettingsSnapshot = settings.notificationVibrations,
            ),
        )
    }

    suspend fun sendCategoryPatternTest(
        category: WearCompanionVibrationCategory,
        customPattern: String,
    ) {
        val generatedAtMs = clock()
        val sample = sampleProvider(category)
        val vibrationSettings = io.element.android.watchbridge.contract.WatchNotificationVibrationSettings(
            groups = if (category == WearCompanionVibrationCategory.GROUPS) WatchNotificationVibrationPattern.CUSTOM else WatchNotificationVibrationPattern.DEFAULT,
            groupsCustomPattern = if (category == WearCompanionVibrationCategory.GROUPS) customPattern else "",
            dms = if (category == WearCompanionVibrationCategory.DMS) WatchNotificationVibrationPattern.CUSTOM else WatchNotificationVibrationPattern.DEFAULT,
            dmsCustomPattern = if (category == WearCompanionVibrationCategory.DMS) customPattern else "",
            favoriteGroups = if (category == WearCompanionVibrationCategory.FAVORITE_GROUPS) WatchNotificationVibrationPattern.CUSTOM else WatchNotificationVibrationPattern.DEFAULT,
            favoriteGroupsCustomPattern = if (category == WearCompanionVibrationCategory.FAVORITE_GROUPS) customPattern else "",
            favoriteDms = if (category == WearCompanionVibrationCategory.FAVORITE_DMS) WatchNotificationVibrationPattern.CUSTOM else WatchNotificationVibrationPattern.DEFAULT,
            favoriteDmsCustomPattern = if (category == WearCompanionVibrationCategory.FAVORITE_DMS) customPattern else "",
        )
        publishSampleNotification(
            pathSuffix = "${category.storageKey()}_custom_test",
            notification = sample.toNotification(
                generatedAtMs = generatedAtMs,
                notificationKeySuffix = "${category.storageKey()}_custom_test",
                roomId = "!$WEAR_COMPANION_TEST_NOTIFICATION_PATH_PREFIX-${category.storageKey()}_custom:local",
                eventId = "\$$WEAR_COMPANION_TEST_NOTIFICATION_PATH_PREFIX-${category.storageKey()}_custom_test-$generatedAtMs:local",
                roomDisplayName = sample.roomDisplayName,
                roomKind = category.roomKind(),
                vibrationPatternOverride = WatchNotificationVibrationPattern.CUSTOM,
                customVibrationPattern = customPattern,
                vibrationSettingsSnapshot = vibrationSettings,
            ),
        )
    }

    suspend fun sendConversationPatternTest(
        room: WatchFavoriteRoom,
        pattern: WatchNotificationVibrationPattern,
        customPattern: String = "",
    ) {
        val generatedAtMs = clock()
        val category = room.toSampleCategory()
        val sample = sampleProvider(category)
        val vibrationSettings = io.element.android.watchbridge.contract.WatchNotificationVibrationSettings(
            groups = WatchNotificationVibrationPattern.DEFAULT,
            conversationOverrides = listOf(
                io.element.android.watchbridge.contract.WatchConversationVibrationOverride(
                    roomId = room.roomId,
                    pattern = pattern,
                    customPattern = customPattern,
                ),
            ),
        )
        publishSampleNotification(
            pathSuffix = "room_${room.roomId}_test",
            notification = sample.toNotification(
                generatedAtMs = generatedAtMs,
                notificationKeySuffix = "room_${room.roomId}_test",
                roomId = room.roomId,
                eventId = "\$$WEAR_COMPANION_TEST_NOTIFICATION_PATH_PREFIX-room-${room.roomId}-$generatedAtMs:local",
                roomDisplayName = room.displayName,
                roomKind = room.kind,
                vibrationPatternOverride = pattern,
                customVibrationPattern = customPattern.takeIf { pattern == WatchNotificationVibrationPattern.CUSTOM },
                vibrationSettingsSnapshot = vibrationSettings,
            ),
        )
    }

    private suspend fun publishSampleNotification(
        pathSuffix: String,
        notification: WatchMessageNotification,
    ) {
        runCatching {
            transport.publishSync(
                path = WatchDataPaths.notification(
                    "$WEAR_COMPANION_TEST_NOTIFICATION_PATH_PREFIX:$pathSuffix",
                ),
                envelope = WatchSyncEnvelope(
                    generatedAtMs = notification.timestampMs,
                    expiresAtMs = notification.timestampMs + WEAR_COMPANION_TEST_NOTIFICATION_TTL_MS,
                    payload = WatchSync.MessageNotification(notification),
                ),
            )
        }.onFailure {
            Timber.w(it, "failed to publish wear companion test notification for pathSuffix=%s", pathSuffix)
        }
    }

    private fun WearCompanionVibrationCategory.roomKind(): WatchRoomKind = when (this) {
        WearCompanionVibrationCategory.GROUPS,
        WearCompanionVibrationCategory.FAVORITE_GROUPS -> WatchRoomKind.GROUP
        WearCompanionVibrationCategory.DMS,
        WearCompanionVibrationCategory.FAVORITE_DMS -> WatchRoomKind.DM
    }

    private fun WearCompanionVibrationCategory.storageKey(): String = name.lowercase(Locale.ROOT)

    private fun WatchFavoriteRoom.toSampleCategory(): WearCompanionVibrationCategory = when {
        isFavorite && kind == WatchRoomKind.DM -> WearCompanionVibrationCategory.FAVORITE_DMS
        isFavorite && kind == WatchRoomKind.GROUP -> WearCompanionVibrationCategory.FAVORITE_GROUPS
        kind == WatchRoomKind.DM -> WearCompanionVibrationCategory.DMS
        else -> WearCompanionVibrationCategory.GROUPS
    }
}

private fun WearCompanionTestNotificationSample.toNotification(
    generatedAtMs: Long,
    notificationKeySuffix: String,
    roomId: String,
    eventId: String,
    roomDisplayName: String,
    roomKind: WatchRoomKind,
    vibrationPatternOverride: WatchNotificationVibrationPattern,
    customVibrationPattern: String? = null,
    vibrationSettingsSnapshot: io.element.android.watchbridge.contract.WatchNotificationVibrationSettings? = null,
): WatchMessageNotification {
    val previewMessages = previewMessages.ifEmpty { listOf(bodyText) }
    return WatchMessageNotification(
        notificationKey = "$WEAR_COMPANION_TEST_NOTIFICATION_PATH_PREFIX:$notificationKeySuffix",
        roomId = roomId,
        eventId = eventId,
        roomDisplayName = roomDisplayName,
        roomKind = roomKind,
        senderDisplayName = senderDisplayName,
        bodyText = previewMessages.last(),
        timestampMs = generatedAtMs,
        messageCount = maxOf(messageCount, previewMessages.size),
        previewMessages = previewMessages.mapIndexed { index, previewBodyText ->
            WatchNotificationMessagePreview(
                senderDisplayName = senderDisplayName,
                bodyText = previewBodyText,
                timestampMs = generatedAtMs - (previewMessages.lastIndex - index),
            )
        },
        isNoisy = true,
        vibrationPatternOverride = vibrationPatternOverride,
        customVibrationPattern = customVibrationPattern,
        vibrationSettingsSnapshot = vibrationSettingsSnapshot,
        isTestNotification = true,
    )
}

private fun defaultTestNotificationSample(
    context: Context,
    category: WearCompanionVibrationCategory,
): WearCompanionTestNotificationSample = when (category) {
    WearCompanionVibrationCategory.GROUPS -> WearCompanionTestNotificationSample(
        roomDisplayName = context.getString(R.string.screen_wear_companion_test_notification_group_room_name),
        senderDisplayName = context.getString(R.string.screen_wear_companion_test_notification_group_sender_name),
        bodyText = context.getString(R.string.screen_wear_companion_test_notification_group_body),
        messageCount = 3,
        previewMessages = listOf(
            "I’ve pushed the latest Wear build to the test track.",
            "Can you compare Triple with Pulse on your watch?",
            context.getString(R.string.screen_wear_companion_test_notification_group_body),
        ),
    )
    WearCompanionVibrationCategory.DMS -> WearCompanionTestNotificationSample(
        roomDisplayName = context.getString(R.string.screen_wear_companion_test_notification_dm_room_name),
        senderDisplayName = context.getString(R.string.screen_wear_companion_test_notification_dm_sender_name),
        bodyText = context.getString(R.string.screen_wear_companion_test_notification_dm_body),
        messageCount = 2,
        previewMessages = listOf(
            "I’m on the train now.",
            context.getString(R.string.screen_wear_companion_test_notification_dm_body),
        ),
    )
    WearCompanionVibrationCategory.FAVORITE_GROUPS -> WearCompanionTestNotificationSample(
        roomDisplayName = context.getString(R.string.screen_wear_companion_test_notification_favorite_group_room_name),
        senderDisplayName = context.getString(R.string.screen_wear_companion_test_notification_favorite_group_sender_name),
        bodyText = context.getString(R.string.screen_wear_companion_test_notification_favorite_group_body),
        messageCount = 4,
        previewMessages = listOf(
            "Release notes are drafted.",
            "QA signed off on the watch notification fixes.",
            "One last sanity check on mark-as-read would be great.",
            context.getString(R.string.screen_wear_companion_test_notification_favorite_group_body),
        ),
    )
    WearCompanionVibrationCategory.FAVORITE_DMS -> WearCompanionTestNotificationSample(
        roomDisplayName = context.getString(R.string.screen_wear_companion_test_notification_favorite_dm_room_name),
        senderDisplayName = context.getString(R.string.screen_wear_companion_test_notification_favorite_dm_sender_name),
        bodyText = context.getString(R.string.screen_wear_companion_test_notification_favorite_dm_body),
        messageCount = 2,
        previewMessages = listOf(
            "Voice message landed cleanly on my watch.",
            context.getString(R.string.screen_wear_companion_test_notification_favorite_dm_body),
        ),
    )
}
