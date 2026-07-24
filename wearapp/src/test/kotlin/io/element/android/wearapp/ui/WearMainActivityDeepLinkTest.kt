/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.element.android.appconfig.WearCompanionConfig
import io.element.android.appconfig.WearCompanionDeepLink
import io.element.android.watchbridge.contract.WatchMessageNotification
import io.element.android.watchbridge.contract.WatchTileConversationAction
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class WearMainActivityDeepLinkTest {

    @Test
    fun `consume pending deep link clears extras after first use`() {
        val intent = Intent()
            .putExtra(WearCompanionConfig.EXTRA_ROOM_ID, "!room:server")
            .putExtra(WearCompanionConfig.EXTRA_EVENT_ID, "\$event:server")

        assertThat(consumePendingDeepLink(intent)).isEqualTo(
            WearCompanionDeepLink(roomId = "!room:server", eventId = "\$event:server"),
        )
        assertThat(intent.hasExtra(WearCompanionConfig.EXTRA_ROOM_ID)).isFalse()
        assertThat(intent.hasExtra(WearCompanionConfig.EXTRA_EVENT_ID)).isFalse()
        assertThat(consumePendingDeepLink(intent)).isNull()
    }

    @Test
    fun `consume pending deep link parses wear deep link uri`() {
        val intent = buildWearLaunchIntent(
            context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            roomId = "!room:server",
            eventId = "\$event:server",
        )

        assertThat(consumePendingDeepLink(intent)).isEqualTo(
            WearCompanionDeepLink(roomId = "!room:server", eventId = "\$event:server"),
        )
        assertThat(intent.data).isNull()
    }

    @Test
    fun `consume pending deep link parses thread uri`() {
        val intent = buildWearLaunchIntent(
            context = ApplicationProvider.getApplicationContext(),
            roomId = "!room:server",
            threadRootEventId = "\$root:server",
        )

        assertThat(consumePendingDeepLink(intent)).isEqualTo(
            WearCompanionDeepLink(roomId = "!room:server", threadRootEventId = "\$root:server"),
        )
        assertThat(intent.data).isNull()
    }

    @Test
    fun `message notification deep link builds room then message back stack`() {
        val routes = wearDeepLinkBackStackRoutes(
            WearCompanionDeepLink(roomId = "!room:server", eventId = "\$event:server"),
        )

        assertThat(routes).containsExactly(
            "room?roomId=!room%3Aserver",
            "message?roomId=!room%3Aserver&eventId=%24event%3Aserver&threadRootId=",
        ).inOrder()
    }

    @Test
    fun `thread message notification deep link builds room thread then message back stack`() {
        val routes = wearDeepLinkBackStackRoutes(
            WearCompanionDeepLink(
                roomId = "!room:server",
                eventId = "\$event:server",
                threadRootEventId = "\$root:server",
            ),
        )

        assertThat(routes).containsExactly(
            "room?roomId=!room%3Aserver",
            "thread?roomId=!room%3Aserver&rootId=%24root%3Aserver",
            "message?roomId=!room%3Aserver&eventId=%24event%3Aserver&threadRootId=%24root%3Aserver",
        ).inOrder()
    }

    @Test
    fun `in-app notification is only eligible on conversation and thread screens`() {
        assertThat(isConversationOrThreadRoute("room?roomId=room")).isTrue()
        assertThat(isConversationOrThreadRoute("thread?roomId=room&rootId=root")).isTrue()
        assertThat(isConversationOrThreadRoute("favorites")).isFalse()
        assertThat(isConversationOrThreadRoute("message?roomId=room&eventId=event")).isFalse()
        assertThat(isConversationOrThreadRoute(null)).isFalse()
    }

    @Test
    fun `in-app thread notification targets exact message with canonical back stack`() {
        val notification = WatchMessageNotification(
            notificationKey = "notification",
            roomId = "!room:server",
            eventId = "\$event:server",
            threadRootEventId = "\$root:server",
            timestampMs = 123L,
        )

        val deepLink = notification.toDeepLink()

        assertThat(wearDeepLinkBackStackRoutes(deepLink)).containsExactly(
            "room?roomId=!room%3Aserver",
            "thread?roomId=!room%3Aserver&rootId=%24root%3Aserver",
            "message?roomId=!room%3Aserver&eventId=%24event%3Aserver&threadRootId=%24root%3Aserver",
        ).inOrder()
    }

    @Test
    fun `tile clickable ids round-trip room ids`() {
        val clickableId = openRoomTileClickableId("!room:server")

        assertThat(parseOpenRoomTileClickableId(clickableId)).isEqualTo("!room:server")
        assertThat(isOpenAppTileClickableId(openAppTileClickableId())).isTrue()
        assertThat(parseOpenRoomTileClickableId(openAppTileClickableId())).isNull()
    }

    @Test
    fun `tile clickable ids round-trip room ids with explicit tile actions`() {
        val clickableId = roomTileClickableId(
            roomId = "!room:server",
            action = WatchTileConversationAction.QUICK_REPLY_VOICE,
        )

        assertThat(parseRoomTileClickableId(clickableId)).isEqualTo(
            TileConversationClickable(
                roomId = "!room:server",
                action = WatchTileConversationAction.QUICK_REPLY_VOICE,
            ),
        )
    }

    @Test
    fun `tile clickable ids carry latest event ids when available`() {
        val clickableId = roomTileClickableId(
            roomId = "!room:server",
            action = WatchTileConversationAction.OPEN_LATEST,
            eventId = "\$event:server",
        )

        assertThat(parseRoomTileClickableId(clickableId)).isEqualTo(
            TileConversationClickable(
                roomId = "!room:server",
                action = WatchTileConversationAction.OPEN_LATEST,
                eventId = "\$event:server",
            ),
        )
    }

    @Test
    fun `consume pending tile direct reply room id clears extra after first use`() {
        val intent = buildWearTileDirectReplyIntent(
            context = ApplicationProvider.getApplicationContext(),
            roomId = "!room:server",
        )

        assertThat(consumePendingTileDirectReplyRoomId(intent)).isEqualTo("!room:server")
        assertThat(consumePendingTileDirectReplyRoomId(intent)).isNull()
    }

    @Test
    fun `consume pending tile read latest clears extras after first use`() {
        val intent = buildWearTileReadLatestIntent(
            context = ApplicationProvider.getApplicationContext(),
            roomId = "!room:server",
            previewText = "Latest message",
        )

        assertThat(consumePendingTileReadLatest(intent)).isEqualTo(
            PendingTileReadLatest(roomId = "!room:server", previewText = "Latest message"),
        )
        assertThat(consumePendingTileReadLatest(intent)).isNull()
    }

    @Test
    fun `voice recorder intent preserves room target after tile quick reply navigation`() {
        val intent = buildVoiceRecorderIntent(
            context = ApplicationProvider.getApplicationContext(),
            roomId = "!room:server",
            roomDisplayName = "Room",
        )

        assertThat(intent.getStringExtra(EXTRA_VOICE_ROOM_ID)).isEqualTo("!room:server")
        assertThat(intent.getStringExtra(EXTRA_VOICE_ROOM_DISPLAY_NAME)).isEqualTo("Room")
    }

    @Test
    fun `dictation launch gate rejects duplicate starts until finished`() {
        val gate = DictationLaunchGate()

        assertThat(gate.tryStart()).isTrue()
        assertThat(gate.tryStart()).isFalse()

        gate.finish()

        assertThat(gate.tryStart()).isTrue()
    }
    @Test
    fun `fresh notification is eligible for in-app banner`() {
        val now = 10_000_000L
        assertThat(shouldShowInAppNotification(notificationAt(now - 1_000L), now)).isTrue()
    }

    @Test
    fun `stale notification is ignored by in-app banner`() {
        val now = 10_000_000L
        assertThat(shouldShowInAppNotification(notificationAt(now - 180_000L), now)).isFalse()
    }

    private fun notificationAt(timestampMs: Long) = WatchMessageNotification(
        notificationKey = "notification",
        roomId = "room",
        eventId = "event",
        roomDisplayName = "Room",
        senderDisplayName = "Sender",
        bodyText = "Body",
        timestampMs = timestampMs,
    )

}
