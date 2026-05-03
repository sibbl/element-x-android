/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.watchbridge

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.timeline.item.event.EventType
import io.element.android.libraries.push.impl.notifications.CompanionNotificationBridge
import io.element.android.libraries.push.impl.notifications.factories.NotificationCreator
import io.element.android.libraries.push.impl.notifications.model.NotifiableMessageEvent
import io.element.android.libraries.push.impl.notifications.model.ResolvedPushEvent
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.services.toolbox.api.strings.StringProvider
import io.element.android.watchbridge.contract.WatchDataPaths
import io.element.android.watchbridge.contract.WatchMessageNotification
import io.element.android.watchbridge.contract.WatchSync
import io.element.android.watchbridge.contract.WatchSyncEnvelope
import io.element.android.watchbridge.transport.PlayServicesWatchTransport
import io.element.android.watchbridge.transport.WatchTransport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val WATCH_NOTIFICATION_TTL_MS = 24L * 60L * 60L * 1000L

@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class)
class WatchNotificationBridgePublisher(
    @ApplicationContext context: Context,
    stringProvider: StringProvider,
) : CompanionNotificationBridge by WatchNotificationBridgePublisherDelegate(
    transport = PlayServicesWatchTransport(context),
    imageLabel = stringProvider.getString(CommonStrings.common_image),
)

internal class WatchNotificationBridgePublisherDelegate(
    private val transport: WatchTransport,
    private val imageLabel: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : CompanionNotificationBridge {

    private val mutex = Mutex()
    private val activeNotifications = LinkedHashMap<String, ActiveNotification>()

    override suspend fun onMessageNotificationsRendered(events: List<NotifiableMessageEvent>) {
        val renderedNotifications = events.asSequence()
            .filterNot { it.outGoingMessage }
            .filterNot { it.type == EventType.RTC_NOTIFICATION }
            .groupBy { ConversationKey.from(it) }
            .mapNotNull { (key, groupedEvents) ->
                groupedEvents.maxByOrNull(NotifiableMessageEvent::timestamp)
                    ?.toRenderedNotification(key, groupedEvents.size)
            }
            .toList()
        if (renderedNotifications.isEmpty()) return

        mutex.withLock {
            renderedNotifications.forEach { renderedNotification ->
                val generatedAtMs = clock()
                transport.publishSync(
                    path = WatchDataPaths.notification(renderedNotification.notification.notificationKey),
                    envelope = WatchSyncEnvelope(
                        generatedAtMs = generatedAtMs,
                        expiresAtMs = generatedAtMs + WATCH_NOTIFICATION_TTL_MS,
                        payload = WatchSync.MessageNotification(renderedNotification.notification),
                    ),
                )
                activeNotifications[renderedNotification.notification.notificationKey] = renderedNotification.activeNotification
            }
        }
    }

    override suspend fun onMessagesClearedForRoom(sessionId: SessionId, roomId: RoomId) {
        deleteNotificationKeys(listOf(ConversationKey(sessionId, roomId, threadId = null).notificationKey))
    }

    override suspend fun onMessagesClearedForThread(sessionId: SessionId, roomId: RoomId, threadId: ThreadId) {
        deleteNotificationKeys(listOf(ConversationKey(sessionId, roomId, threadId).notificationKey))
    }

    override suspend fun onAllMessagesCleared(sessionId: SessionId) {
        deleteNotificationKeys(keysMatching { it.sessionId == sessionId })
    }

    override suspend fun onSessionCleared(sessionId: SessionId) {
        deleteNotificationKeys(keysMatching { it.sessionId == sessionId })
    }

    override suspend fun onMessageNotificationsRedacted(redactions: List<ResolvedPushEvent.Redaction>) {
        if (redactions.isEmpty()) return
        val redactedEvents = redactions.map { redaction -> redaction.sessionId to redaction.redactedEventId.value }.toSet()
        deleteNotificationKeys(
            keysMatching { activeNotification ->
                (activeNotification.sessionId to activeNotification.eventId) in redactedEvents
            },
        )
    }

    private suspend fun deleteNotificationKeys(keys: Collection<String>) {
        val distinctKeys = keys.distinct()
        if (distinctKeys.isEmpty()) return
        distinctKeys.forEach { notificationKey ->
            transport.deleteSync(WatchDataPaths.notification(notificationKey))
        }
        mutex.withLock {
            distinctKeys.forEach(activeNotifications::remove)
        }
    }

    private suspend fun keysMatching(predicate: (ActiveNotification) -> Boolean): List<String> = mutex.withLock {
        activeNotifications
            .filterValues(predicate)
            .keys
            .toList()
    }

    private fun NotifiableMessageEvent.toRenderedNotification(
        key: ConversationKey,
        messageCount: Int,
    ): RenderedNotification {
        val bodyText = body?.takeIf { it.isNotBlank() }
            ?: imageMimeType?.let { imageLabel }
        val displayName = roomName?.takeIf { it.isNotBlank() }
            ?: senderDisambiguatedDisplayName.orEmpty()
        return RenderedNotification(
            notification = WatchMessageNotification(
                notificationKey = key.notificationKey,
                roomId = roomId.value,
                eventId = eventId.value,
                threadRootEventId = threadId?.value,
                roomDisplayName = displayName,
                senderDisplayName = senderDisambiguatedDisplayName?.takeIf { it.isNotBlank() },
                bodyText = bodyText,
                timestampMs = timestamp,
                messageCount = messageCount,
                isNoisy = noisy,
            ),
            activeNotification = ActiveNotification(
                sessionId = sessionId,
                roomId = roomId,
                threadId = threadId,
                eventId = eventId.value,
            ),
        )
    }
}

private data class ConversationKey(
    val sessionId: SessionId,
    val roomId: RoomId,
    val threadId: ThreadId?,
) {
    val notificationKey: String = "message:${sessionId.value}:${NotificationCreator.messageTag(roomId, threadId)}"

    companion object {
        fun from(event: NotifiableMessageEvent): ConversationKey = ConversationKey(
            sessionId = event.sessionId,
            roomId = event.roomId,
            threadId = event.threadId,
        )
    }
}

private data class ActiveNotification(
    val sessionId: SessionId,
    val roomId: RoomId,
    val threadId: ThreadId?,
    val eventId: String,
)

private data class RenderedNotification(
    val notification: WatchMessageNotification,
    val activeNotification: ActiveNotification,
)