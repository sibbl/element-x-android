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
import androidx.wear.activity.ConfirmationActivity
import io.element.android.watchbridge.contract.WatchCommand
import io.element.android.watchbridge.contract.WatchSendSource
import io.element.android.wearapp.WearApp
import io.element.android.wearapp.ui.buildWearLaunchIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

class WearNotificationActionReceiver : BroadcastReceiver {
    private val executor: WearNotificationActionExecutor
    private val uiController: WearNotificationActionUiController
    private val actionScope: CoroutineScope

    constructor() : super() {
        executor = BridgeWearNotificationActionExecutor()
        uiController = SystemWearNotificationActionUiController()
        actionScope = defaultActionScope
    }

    internal constructor(
        executor: WearNotificationActionExecutor,
        uiController: WearNotificationActionUiController,
        actionScope: CoroutineScope = defaultActionScope,
    ) : super() {
        this.executor = executor
        this.uiController = uiController
        this.actionScope = actionScope
    }

    override fun onReceive(context: Context, intent: Intent) {
        val notificationKey = intent.getStringExtra(EXTRA_NOTIFICATION_KEY) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, wearLocalNotificationId(notificationKey))
        val generatedAtMs = intent.getLongExtra(EXTRA_GENERATED_AT_MS, System.currentTimeMillis())

        when (intent.action) {
            ACTION_DISMISS -> {
                uiController.dismiss(notificationKey, notificationId, generatedAtMs, context)
            }

            ACTION_MARK_AS_READ,
            ACTION_REPLY -> {
                if (intent.action == ACTION_MARK_AS_READ) {
                    if (!intent.getBooleanExtra(EXTRA_SKIP_CONFIRMATION, false)) {
                        uiController.showMarkAsReadConfirmation(context)
                    }
                    uiController.dismiss(notificationKey, notificationId, generatedAtMs, context)
                }
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

        val result = when (intent.action) {
            ACTION_MARK_AS_READ -> executor.markAsRead(
                context = context,
                roomId = roomId,
                eventId = eventId,
                threadRootEventId = threadRootEventId,
            )
            ACTION_REPLY -> {
                val replyText = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(RESULT_KEY_REPLY_TEXT)
                    ?.toString()
                    ?.trim()
                    .orEmpty()
                if (replyText.isBlank()) return
                executor.reply(
                    context = context,
                    roomId = roomId,
                    eventId = eventId,
                    threadRootEventId = threadRootEventId,
                    replyText = replyText,
                )
            }
            else -> return
        }

        result.onSuccess {
            if (intent.action == ACTION_REPLY) {
                uiController.dismiss(notificationKey, notificationId, generatedAtMs, context)
                uiController.openMessageDetail(
                    context = context,
                    roomId = roomId,
                    eventId = eventId,
                    threadRootEventId = threadRootEventId,
                )
            }
        }.onFailure {
            Timber.w(it, "Watch notification action failed action=%s roomId=%s eventId=%s", intent.action, roomId, eventId)
        }
    }

    internal interface WearNotificationActionExecutor {
        suspend fun markAsRead(
            context: Context,
            roomId: String,
            eventId: String,
            threadRootEventId: String?,
        ): Result<Unit>

        suspend fun reply(
            context: Context,
            roomId: String,
            eventId: String,
            threadRootEventId: String?,
            replyText: String,
        ): Result<Unit>
    }

    internal interface WearNotificationActionUiController {
        fun showMarkAsReadConfirmation(context: Context)

        fun dismiss(
            notificationKey: String,
            notificationId: Int,
            generatedAtMs: Long,
            context: Context,
        )

        fun openMessageDetail(
            context: Context,
            roomId: String,
            eventId: String,
            threadRootEventId: String?,
        )
    }

    internal class BridgeWearNotificationActionExecutor : WearNotificationActionExecutor {
        override suspend fun markAsRead(
            context: Context,
            roomId: String,
            eventId: String,
            threadRootEventId: String?,
        ): Result<Unit> = runCatching {
            val bridgeClient = (context.applicationContext as WearApp).bridgeClient
            bridgeClient.sendAwaitTerminalAck { requestId ->
                WatchCommand.MarkAsRead(
                    requestId = requestId,
                    roomId = roomId,
                    eventId = eventId,
                    threadRootEventId = threadRootEventId,
                )
            }
            Unit
        }

        override suspend fun reply(
            context: Context,
            roomId: String,
            eventId: String,
            threadRootEventId: String?,
            replyText: String,
        ): Result<Unit> = runCatching {
            val bridgeClient = (context.applicationContext as WearApp).bridgeClient
            bridgeClient.sendTextWithLocalEcho(
                roomId = roomId,
                threadRootEventId = threadRootEventId,
                inReplyToEventId = eventId.takeUnless { threadRootEventId != null },
                text = replyText,
                source = WatchSendSource.QUICK_REPLY,
            )
            Unit
        }
    }

    internal class SystemWearNotificationActionUiController : WearNotificationActionUiController {
        override fun showMarkAsReadConfirmation(context: Context) {
            val intent = Intent(context, ConfirmationActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(ConfirmationActivity.EXTRA_ANIMATION_TYPE, ConfirmationActivity.SUCCESS_ANIMATION)
                .putExtra(ConfirmationActivity.EXTRA_ANIMATION_DURATION_MILLIS, MARK_AS_READ_CONFIRMATION_MS)
            runCatching {
                context.startActivity(intent)
            }.onFailure {
                Timber.w(it, "Unable to show watch notification mark-as-read confirmation")
            }
        }

        override fun dismiss(
            notificationKey: String,
            notificationId: Int,
            generatedAtMs: Long,
            context: Context,
        ) {
            WearNotificationDismissalStore(context).recordDismissal(notificationKey, generatedAtMs)
            NotificationManagerCompat.from(context).cancel(notificationId)
        }

        override fun openMessageDetail(
            context: Context,
            roomId: String,
            eventId: String,
            threadRootEventId: String?,
        ) {
            runCatching {
                context.startActivity(
                    buildWearLaunchIntent(
                        context = context,
                        roomId = roomId,
                        eventId = eventId,
                        threadRootEventId = threadRootEventId,
                    ),
                )
            }.onFailure {
                Timber.w(it, "Unable to open watch message detail from notification action")
            }
        }
    }

    companion object {
        internal const val ACTION_MARK_AS_READ = "io.element.android.wearapp.notifications.MARK_AS_READ"
        internal const val ACTION_REPLY = "io.element.android.wearapp.notifications.REPLY"
        internal const val ACTION_DISMISS = "io.element.android.wearapp.notifications.DISMISS"

        internal const val EXTRA_NOTIFICATION_KEY = "extra_notification_key"
        internal const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        internal const val EXTRA_GENERATED_AT_MS = "extra_generated_at_ms"
        internal const val EXTRA_ROOM_ID = "extra_room_id"
        internal const val EXTRA_EVENT_ID = "extra_event_id"
        internal const val EXTRA_THREAD_ROOT_EVENT_ID = "extra_thread_root_event_id"
        internal const val EXTRA_SKIP_CONFIRMATION = "extra_skip_confirmation"

        internal const val RESULT_KEY_REPLY_TEXT = "result_key_reply_text"

        private const val MARK_AS_READ_CONFIRMATION_MS = 900

        private val defaultActionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
