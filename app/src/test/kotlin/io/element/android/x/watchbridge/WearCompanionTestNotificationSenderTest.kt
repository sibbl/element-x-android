/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.watchbridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.element.android.watchbridge.contract.WatchCompanionSettings
import io.element.android.watchbridge.contract.WatchFavoriteRoom
import io.element.android.watchbridge.contract.WatchDataPaths
import io.element.android.watchbridge.contract.WatchNotificationMessagePreview
import io.element.android.watchbridge.contract.WatchNotificationVibrationPattern
import io.element.android.watchbridge.contract.WatchNotificationVibrationSettings
import io.element.android.watchbridge.contract.WatchRoomKind
import io.element.android.watchbridge.contract.WatchSync
import io.element.android.watchbridge.contract.WatchSyncEnvelope
import io.element.android.watchbridge.transport.WatchChannel
import io.element.android.watchbridge.transport.WatchTransport
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WearCompanionTestNotificationSenderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `sender publishes a category specific watch local sample notification`() = runTest {
        val transport = RecordingTransport()
        val sample = WearCompanionTestNotificationSample(
            roomDisplayName = "Element X Android",
            senderDisplayName = "Aisha",
            bodyText = "Can you try the new vibration presets on watch?",
            messageCount = 3,
            previewMessages = listOf(
                "I’ve pushed a fresh watch build.",
                "Can you try the new vibration presets on watch?",
                "Mark-as-read should dismiss instantly now.",
            ),
        )
        val sender = WearCompanionTestNotificationSender(
            context = context,
            transport = transport,
            clock = { 1_234L },
            sampleProvider = { sample },
        )

        sender.send(
            category = WearCompanionVibrationCategory.GROUPS,
            settings = WatchCompanionSettings(
                notificationVibrations = WatchNotificationVibrationSettings(
                    groups = WatchNotificationVibrationPattern.TRIPLE,
                    dms = WatchNotificationVibrationPattern.DEFAULT,
                    favoriteGroups = WatchNotificationVibrationPattern.DEFAULT,
                    favoriteDms = WatchNotificationVibrationPattern.DEFAULT,
                ),
            ),
        )

        val publication = transport.publications.single()
        val payload = publication.second.payload as WatchSync.MessageNotification

        assertThat(publication.first)
            .isEqualTo(WatchDataPaths.notification("wear-companion-test:groups"))
        assertThat(publication.second.expiresAtMs).isEqualTo(1_234L + 45_000L)
        assertThat(payload.notification.roomKind).isEqualTo(WatchRoomKind.GROUP)
        assertThat(payload.notification.vibrationPatternOverride)
            .isEqualTo(WatchNotificationVibrationPattern.TRIPLE)
        assertThat(payload.notification.notificationKey)
            .isEqualTo("wear-companion-test:groups")
        assertThat(payload.notification.roomDisplayName).isEqualTo(sample.roomDisplayName)
        assertThat(payload.notification.senderDisplayName).isEqualTo(sample.senderDisplayName)
        assertThat(payload.notification.bodyText).isEqualTo(sample.previewMessages.last())
        assertThat(payload.notification.messageCount).isEqualTo(sample.messageCount)
        assertThat(payload.notification.previewMessages).containsExactly(
            WatchNotificationMessagePreview(
                senderDisplayName = sample.senderDisplayName,
                bodyText = sample.previewMessages[0],
                timestampMs = 1_232L,
            ),
            WatchNotificationMessagePreview(
                senderDisplayName = sample.senderDisplayName,
                bodyText = sample.previewMessages[1],
                timestampMs = 1_233L,
            ),
            WatchNotificationMessagePreview(
                senderDisplayName = sample.senderDisplayName,
                bodyText = sample.previewMessages[2],
                timestampMs = 1_234L,
            ),
        ).inOrder()
    }

    @Test
    fun `sender publishes a saved category custom vibration pattern`() = runTest {
        val transport = RecordingTransport()
        val sender = WearCompanionTestNotificationSender(
            context = context,
            transport = transport,
            clock = { 1_500L },
            sampleProvider = {
                WearCompanionTestNotificationSample(
                    roomDisplayName = "Taylor",
                    senderDisplayName = "Taylor",
                    bodyText = "Custom buzz test.",
                )
            },
        )

        sender.send(
            category = WearCompanionVibrationCategory.FAVORITE_DMS,
            settings = WatchCompanionSettings(
                notificationVibrations = WatchNotificationVibrationSettings(
                    favoriteDms = WatchNotificationVibrationPattern.TRIPLE,
                ),
            ),
        )

        val publication = transport.publications.single()
        val payload = publication.second.payload as WatchSync.MessageNotification

        assertThat(publication.first)
            .isEqualTo(WatchDataPaths.notification("wear-companion-test:favorite_dms"))
        assertThat(payload.notification.roomKind).isEqualTo(WatchRoomKind.DM)
        assertThat(payload.notification.vibrationPatternOverride).isEqualTo(WatchNotificationVibrationPattern.TRIPLE)
    }

    @Test
    fun `conversation custom test preserves room category and custom payload`() = runTest {
        val transport = RecordingTransport()
        val room = WatchFavoriteRoom(roomId = "!dm:server", displayName = "Test", kind = WatchRoomKind.DM, isFavorite = true)
        val sender = WearCompanionTestNotificationSender(
            context,
            transport,
            clock = { 2_000L },
            sampleProvider = { WearCompanionTestNotificationSample("Test", "Test", "Test") },
        )

        sender.sendConversationPatternTest(room, WatchNotificationVibrationPattern.CUSTOM, "0,100,50,200")

        val payload = transport.publications.single().second.payload as WatchSync.MessageNotification
        assertThat(payload.notification.roomId).isEqualTo(room.roomId)
        assertThat(payload.notification.roomKind).isEqualTo(WatchRoomKind.DM)
        assertThat(payload.notification.vibrationPatternOverride).isEqualTo(WatchNotificationVibrationPattern.CUSTOM)
        assertThat(payload.notification.customVibrationPattern).isEqualTo("0,100,50,200")
        assertThat(payload.notification.vibrationSettingsSnapshot?.conversationOverrides?.single()?.customPattern)
            .isEqualTo("0,100,50,200")
    }

    private class RecordingTransport : WatchTransport {
        val publications = mutableListOf<Pair<String, WatchSyncEnvelope>>()

        override suspend fun publishSync(path: String, envelope: WatchSyncEnvelope, urgent: Boolean) {
            publications += path to envelope
        }

        override suspend fun sendMessage(path: String, envelope: WatchSyncEnvelope): String = "node"

        override suspend fun deleteSync(path: String) = Unit

        override suspend fun openChannel(path: String): WatchChannel? = null
    }
}
