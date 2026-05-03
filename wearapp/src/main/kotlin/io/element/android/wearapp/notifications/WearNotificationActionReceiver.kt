/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import io.element.android.watchbridge.contract.WatchCommand
import io.element.android.watchbridge.contract.WatchSendSource
import io.element.android.wearapp.WearApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

class WearNotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val notificationKey = intent.getStringExtra(EXTRA_NOTIFICATION_KEY) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, wearLocalNotificationId(notificationKey))
        val generatedAtMs = intent.getLongExtra(EXTRA_GENERATED_AT_MS, System.currentTimeMillis())

        when (intent.action) {
            ACTION_DISMISS -> {
                WearNotificationDismissalStore(context).recordDismissal(notificationKey, generatedAtMs)
            }

            ACTION_REPLY -> {
                val pendingResult = goAsync()
                actionScope.launch {
                    try {
                        handleInteractiveAction(
                            context = context,
                            intent = intent,
                            notificationKey = notificationKey,
                            notificationId = notificationId,
                            generatedAtMs = generatedAtMs,
                        )
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    private suspend fun handleInteractiveAction(
        context: Context,
        intent: Intent,
        notificationKey: String,
        notificationId: Int,
        generatedAtMs: Long,
    ) {
        val roomId = intent.getStringExtra(EXTRA_ROOM_ID).orEmpty()
        val eventId = intent.getStringExtra(EXTRA_EVENT_ID).orEmpty()
        val threadRootEventId = intent.getStringExtra(EXTRA_THREAD_ROOT_EVENT_ID)?.takeIf { it.isNotBlank() }
        if (roomId.isBlank() || eventId.isBlank()) return

        val bridgeClient = (context.applicationContext as WearApp).bridgeClient
        val result = runCatching {
            when (intent.action) {
                ACTION_REPLY -> {
                    val replyText = RemoteInput.getResultsFromIntent(intent)
                        ?.getCharSequence(RESULT_KEY_REPLY_TEXT)
                        ?.toString()
                        ?.trim()
                        .orEmpty()
                    if (replyText.isBlank()) return
                    bridgeClient.sendAwaitTerminalAck { requestId ->
                        WatchCommand.SendText(
                            requestId = requestId,
                            roomId = roomId,
                            threadRootEventId = threadRootEventId,
                            inReplyToEventId = eventId.takeUnless { threadRootEventId != null },
                            text = replyText,
                            source = WatchSendSource.QUICK_REPLY,
                            clientTsMs = System.currentTimeMillis(),
                        )
                    }
                }
            }
        }

        result.onSuccess {
            WearNotificationDismissalStore(context).recordDismissal(notificationKey, generatedAtMs)
            NotificationManagerCompat.from(context).cancel(notificationId)
        }.onFailure {
            Timber.w(it, "Watch notification action failed action=%s roomId=%s eventId=%s", intent.action, roomId, eventId)
        }
    }

    companion object {
        internal const val ACTION_REPLY = "io.element.android.wearapp.notifications.REPLY"
        internal const val ACTION_DISMISS = "io.element.android.wearapp.notifications.DISMISS"

        internal const val EXTRA_NOTIFICATION_KEY = "extra_notification_key"
        internal const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        internal const val EXTRA_GENERATED_AT_MS = "extra_generated_at_ms"
        internal const val EXTRA_ROOM_ID = "extra_room_id"
        internal const val EXTRA_EVENT_ID = "extra_event_id"
        internal const val EXTRA_THREAD_ROOT_EVENT_ID = "extra_thread_root_event_id"

        internal const val RESULT_KEY_REPLY_TEXT = "result_key_reply_text"

        private val actionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}