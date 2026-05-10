/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.notifications

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.push.impl.notifications.factories.aNotificationAccountParams
import io.element.android.libraries.push.impl.notifications.fake.FakeNotificationCreator
import io.element.android.services.toolbox.test.strings.FakeStringProvider
import io.element.android.services.toolbox.test.systemclock.A_FAKE_TIMESTAMP
import io.element.android.tests.testutils.lambda.any
import io.element.android.tests.testutils.lambda.lambdaRecorder
import io.element.android.tests.testutils.lambda.nonNull
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultSummaryGroupMessageCreatorTest : RobolectricTest() {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `process notifications`() = runTest {
        val notificationCreator = FakeNotificationCreator()
        val summaryCreator = DefaultSummaryGroupMessageCreator(
            stringProvider = FakeStringProvider(),
            notificationCreator = notificationCreator,
        )

        val result = summaryCreator.createSummaryNotification(
            notificationAccountParams = aNotificationAccountParams(),
            roomNotifications = listOf(
                RoomNotification(
                    notification = Notification(),
                    roomId = A_ROOM_ID,
                    roomDisplayName = "A Room",
                    messageCount = 1,
                    latestTimestamp = A_FAKE_TIMESTAMP + 10,
                    shouldBing = true,
                    threadId = null,
                )
            ),
            invitationNotifications = emptyList(),
            simpleNotifications = emptyList(),
        )

        notificationCreator.createSummaryListNotificationResult.assertions()
            .isCalledOnce()
            .with(any(), any(), nonNull(), any(), any())

        // Set from the events included
        @Suppress("DEPRECATION")
        assertThat(result.priority).isEqualTo(NotificationCompat.PRIORITY_DEFAULT)
    }

    @Test
    fun `summary line uses latest room message when available`() = runTest {
        var capturedSummaryLines: List<String>? = null
        val notificationCreator = FakeNotificationCreator(
            createSummaryListNotificationResult = lambdaRecorder { _, _, _, _, summaryLines ->
                capturedSummaryLines = summaryLines
                Notification()
            }
        )
        val summaryCreator = DefaultSummaryGroupMessageCreator(
            stringProvider = FakeStringProvider(),
            notificationCreator = notificationCreator,
        )

        summaryCreator.createSummaryNotification(
            notificationAccountParams = aNotificationAccountParams(),
            roomNotifications = listOf(
                RoomNotification(
                    notification = roomMessageNotification(senderName = "Alice", message = "Can you review this?"),
                    roomId = A_ROOM_ID,
                    roomDisplayName = "A Room",
                    messageCount = 1,
                    latestTimestamp = A_FAKE_TIMESTAMP + 10,
                    shouldBing = true,
                    threadId = null,
                )
            ),
            invitationNotifications = emptyList(),
            simpleNotifications = emptyList(),
        )

        assertThat(capturedSummaryLines).containsExactly("A Room: Alice: Can you review this?")
    }

    private fun roomMessageNotification(
        senderName: String,
        message: String,
    ): Notification {
        val currentUser = Person.Builder()
            .setName("Me")
            .build()
        val sender = Person.Builder()
            .setName(senderName)
            .build()
        val style = NotificationCompat.MessagingStyle(currentUser).also {
            it.conversationTitle = "A Room"
            it.addMessage(message, A_FAKE_TIMESTAMP, sender)
        }
        return NotificationCompat.Builder(context, "test")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setStyle(style)
            .build()
    }
}
