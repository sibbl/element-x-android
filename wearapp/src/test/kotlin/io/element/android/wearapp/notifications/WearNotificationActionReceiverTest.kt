/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.notifications

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WearNotificationActionReceiverTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `mark as read dismisses notification immediately and still executes command`() {
        runBlocking {
            val dismissed = mutableListOf<DismissCall>()
            val executed = mutableListOf<Pair<String, String>>()
            val receiver = WearNotificationActionReceiver(
                executor = object : WearNotificationActionReceiver.WearNotificationActionExecutor {
                    override suspend fun markAsRead(
                        context: Context,
                        roomId: String,
                        eventId: String,
                    ): Result<Unit> {
                        executed += roomId to eventId
                        return Result.success(Unit)
                    }

                    override suspend fun reply(
                        context: Context,
                        roomId: String,
                        eventId: String,
                        threadRootEventId: String?,
                        replyText: String,
                    ): Result<Unit> = Result.success(Unit)
                },
                uiController = object : WearNotificationActionReceiver.WearNotificationActionUiController {
                    override fun dismiss(
                        notificationKey: String,
                        notificationId: Int,
                        generatedAtMs: Long,
                        context: Context,
                    ) {
                        dismissed += DismissCall(notificationKey, notificationId, generatedAtMs)
                    }
                },
                actionScope = CoroutineScope(Dispatchers.Unconfined),
            )

            receiver.onReceive(
                context,
                Intent(context, WearNotificationActionReceiver::class.java)
                    .setAction(WearNotificationActionReceiver.ACTION_MARK_AS_READ)
                    .putExtra(WearNotificationActionReceiver.EXTRA_NOTIFICATION_KEY, "notif-1")
                    .putExtra(WearNotificationActionReceiver.EXTRA_NOTIFICATION_ID, 42)
                    .putExtra(WearNotificationActionReceiver.EXTRA_GENERATED_AT_MS, 123L)
                    .putExtra(WearNotificationActionReceiver.EXTRA_ROOM_ID, "!room:server")
                    .putExtra(WearNotificationActionReceiver.EXTRA_EVENT_ID, "\$event:server"),
            )

            assertThat(dismissed).containsExactly(DismissCall("notif-1", 42, 123L))
            assertThat(executed).containsExactly("!room:server" to "\$event:server")
        }
    }

    @Test
    fun `reply dismisses only after successful execution`() {
        runBlocking {
            val dismissed = mutableListOf<DismissCall>()
            val executedReplies = mutableListOf<String>()
            val receiver = WearNotificationActionReceiver(
                executor = object : WearNotificationActionReceiver.WearNotificationActionExecutor {
                    override suspend fun markAsRead(
                        context: Context,
                        roomId: String,
                        eventId: String,
                    ): Result<Unit> = Result.success(Unit)

                    override suspend fun reply(
                        context: Context,
                        roomId: String,
                        eventId: String,
                        threadRootEventId: String?,
                        replyText: String,
                    ): Result<Unit> {
                        executedReplies += replyText
                        return Result.success(Unit)
                    }
                },
                uiController = object : WearNotificationActionReceiver.WearNotificationActionUiController {
                    override fun dismiss(
                        notificationKey: String,
                        notificationId: Int,
                        generatedAtMs: Long,
                        context: Context,
                    ) {
                        dismissed += DismissCall(notificationKey, notificationId, generatedAtMs)
                    }
                },
                actionScope = CoroutineScope(Dispatchers.Unconfined),
            )

            val intent = Intent(context, WearNotificationActionReceiver::class.java)
                .setAction(WearNotificationActionReceiver.ACTION_REPLY)
                .putExtra(WearNotificationActionReceiver.EXTRA_NOTIFICATION_KEY, "notif-2")
                .putExtra(WearNotificationActionReceiver.EXTRA_NOTIFICATION_ID, 24)
                .putExtra(WearNotificationActionReceiver.EXTRA_GENERATED_AT_MS, 456L)
                .putExtra(WearNotificationActionReceiver.EXTRA_ROOM_ID, "!room:server")
                .putExtra(WearNotificationActionReceiver.EXTRA_EVENT_ID, "\$event:server")
            val remoteInputResults = android.os.Bundle().apply {
                putCharSequence(WearNotificationActionReceiver.RESULT_KEY_REPLY_TEXT, "Sounds good")
            }
            androidx.core.app.RemoteInput.addResultsToIntent(
                arrayOf(
                    androidx.core.app.RemoteInput.Builder(WearNotificationActionReceiver.RESULT_KEY_REPLY_TEXT).build(),
                ),
                intent,
                remoteInputResults,
            )

            receiver.onReceive(context, intent)

            assertThat(executedReplies).containsExactly("Sounds good")
            assertThat(dismissed).containsExactly(DismissCall("notif-2", 24, 456L))
        }
    }

    private data class DismissCall(
        val notificationKey: String,
        val notificationId: Int,
        val generatedAtMs: Long,
    )
}