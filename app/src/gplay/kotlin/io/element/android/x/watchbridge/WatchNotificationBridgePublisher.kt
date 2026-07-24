/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.watchbridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import io.element.android.watchbridge.contract.WatchBridgeSerialization
import io.element.android.watchbridge.contract.WatchCompanionSettings
import io.element.android.watchbridge.contract.WatchDataPaths
import io.element.android.watchbridge.contract.WatchMessageNotification
import io.element.android.watchbridge.contract.WatchNotificationMessagePreview
import io.element.android.watchbridge.contract.WatchNotificationVibrationPattern
import io.element.android.watchbridge.contract.WatchProtocol
import io.element.android.watchbridge.contract.WatchRoomKind
import io.element.android.watchbridge.contract.WatchSync
import io.element.android.watchbridge.contract.WatchSyncEnvelope
import io.element.android.watchbridge.transport.PlayServicesWatchTransport
import io.element.android.watchbridge.transport.WatchTransport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

private const val WATCH_NOTIFICATION_TTL_MS = 24L * 60L * 60L * 1000L
private const val NOTIFICATION_IMAGE_MAX_DIMENSION_PX = 320
private const val NOTIFICATION_IMAGE_MAX_BYTES = 18 * 1024
private const val NOTIFICATION_IMAGE_INITIAL_QUALITY = 76
private const val NOTIFICATION_IMAGE_MIN_QUALITY = 42
private const val NOTIFICATION_IMAGE_QUALITY_STEP = 9
private const val MAX_NOTIFICATION_PREVIEW_MESSAGES = 4
private const val MAX_NOTIFICATION_PREVIEW_SENDER_LENGTH = 48
private const val MAX_NOTIFICATION_MESSAGE_BODY_LENGTH = 4_000
private val NOTIFICATION_PREVIEW_WHITESPACE_REGEX = "\\s+".toRegex()
private val NOTIFICATION_BODY_INLINE_WHITESPACE_REGEX = "[\\t\\x0B\\f ]+".toRegex()
private val NOTIFICATION_BODY_EXCESSIVE_BLANK_LINES_REGEX = "\\n{3,}".toRegex()

@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class)
class WatchNotificationBridgePublisher(
    @ApplicationContext context: Context,
    stringProvider: StringProvider,
) : CompanionNotificationBridge by WatchNotificationBridgePublisherDelegate(
    transport = PlayServicesWatchTransport(context),
    imageLabel = stringProvider.getString(CommonStrings.common_image),
    imagePreviewLoader = context::loadNotificationImagePreview,
    settingsProvider = { ElementXWatchBridgeRuntime.settingsStore(context).settings.value },
    clearWatchStateForSession = { sessionId ->
        ElementXWatchBridgeRuntime.clearSessionState(context, sessionId)
    },
)

internal class WatchNotificationBridgePublisherDelegate(
    private val transport: WatchTransport,
    private val imageLabel: String,
    private val imagePreviewLoader: (NotifiableMessageEvent) -> ByteArray? = { null },
    private val settingsProvider: () -> WatchCompanionSettings = { WatchCompanionSettings() },
    private val clearWatchStateForSession: suspend (SessionId) -> Unit = {},
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
                val sortedEvents = groupedEvents.sortedWith(
                    compareBy<NotifiableMessageEvent> { it.timestamp }
                        .thenBy { it.eventId.value },
                )
                sortedEvents.lastOrNull()?.toRenderedNotification(key, sortedEvents)
            }
            .toList()
        if (renderedNotifications.isEmpty()) return

        mutex.withLock {
            renderedNotifications.forEach { renderedNotification ->
                val generatedAtMs = clock()
                val envelope = WatchSyncEnvelope(
                    generatedAtMs = generatedAtMs,
                    expiresAtMs = generatedAtMs + WATCH_NOTIFICATION_TTL_MS,
                    payload = WatchSync.MessageNotification(renderedNotification.notification),
                ).withoutImagePreviewIfOversized()
                val notification = (envelope.payload as WatchSync.MessageNotification).notification
                transport.publishSync(
                    path = WatchDataPaths.notification(notification.notificationKey),
                    envelope = envelope,
                )
                activeNotifications[notification.notificationKey] = renderedNotification.activeNotification
            }
        }
    }

    override suspend fun onMessagesClearedForRoom(sessionId: SessionId, roomId: RoomId) {
        deleteNotificationKeys(listOf(ConversationKey(sessionId, roomId).notificationKey))
    }

    override suspend fun onMessagesClearedForThread(sessionId: SessionId, roomId: RoomId, threadId: ThreadId) {
        deleteNotificationKeys(
            keysMatching { active ->
                active.sessionId == sessionId && active.roomId == roomId && active.threadId == threadId
            },
        )
    }

    override suspend fun onAllMessagesCleared(sessionId: SessionId) {
        deleteNotificationKeys(keysMatching { it.sessionId == sessionId })
    }

    override suspend fun onSessionCleared(sessionId: SessionId) {
        clearWatchStateForSession(sessionId)
        deleteNotificationKeys(keysMatching { it.sessionId == sessionId })
    }

    override suspend fun onMessageNotificationsRedacted(redactions: List<ResolvedPushEvent.Redaction>) {
        if (redactions.isEmpty()) return
        val redactedEvents = redactions.map { redaction -> redaction.sessionId to redaction.redactedEventId.value }.toSet()
        deleteNotificationKeys(
            keysMatching { activeNotification ->
                activeNotification.eventIds.any { eventId -> (activeNotification.sessionId to eventId) in redactedEvents }
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
        groupedEvents: List<NotifiableMessageEvent>,
    ): RenderedNotification {
        val bodyText = watchNotificationBodyText()
        val displayName = roomName?.takeIf { it.isNotBlank() }
            ?: senderDisambiguatedDisplayName.orEmpty()
        val settings = settingsProvider()
        return RenderedNotification(
            notification = WatchMessageNotification(
                notificationKey = key.notificationKey,
                roomId = roomId.value,
                eventId = eventId.value,
                threadRootEventId = threadId?.value,
                roomDisplayName = displayName,
                roomKind = if (roomIsDm) WatchRoomKind.DM else WatchRoomKind.GROUP,
                senderDisplayName = senderDisambiguatedDisplayName?.takeIf { it.isNotBlank() },
                bodyText = bodyText,
                timestampMs = timestamp,
                messageCount = groupedEvents.size,
                previewMessages = groupedEvents
                    .takeLast(MAX_NOTIFICATION_PREVIEW_MESSAGES)
                    .mapNotNull { event -> event.toPreviewMessage() },
                isNoisy = noisy,
                imagePreviewBytes = imagePreviewLoader(this),
                vibrationSettingsSnapshot = settings.notificationVibrations,
            ),
            activeNotification = ActiveNotification(
                sessionId = sessionId,
                roomId = roomId,
                threadId = threadId,
                eventId = eventId.value,
                eventIds = groupedEvents.mapTo(mutableSetOf()) { it.eventId.value },
            ),
        )
    }

    private fun NotifiableMessageEvent.watchNotificationBodyText(): String? {
        val messageText = body
            ?.preserveNotificationMessageText(MAX_NOTIFICATION_MESSAGE_BODY_LENGTH)
            ?.takeIf { it.isNotBlank() }
        return messageText ?: imageMimeType?.let { imageLabel }
    }

    private fun NotifiableMessageEvent.toPreviewMessage(): WatchNotificationMessagePreview? {
        val previewBody = watchNotificationBodyText() ?: return null
        return WatchNotificationMessagePreview(
            senderDisplayName = senderDisambiguatedDisplayName
                ?.compactNotificationLabelText(MAX_NOTIFICATION_PREVIEW_SENDER_LENGTH)
                ?.takeIf { it.isNotBlank() },
            bodyText = previewBody,
            timestampMs = timestamp,
        )
    }
}

private data class ConversationKey(
    val sessionId: SessionId,
    val roomId: RoomId,
) {
    // A thread is part of its room conversation. Keeping one room-level key ensures thread
    // replies use the room classification and room vibration override on Wear OS.
    val notificationKey: String = "message:${sessionId.value}:${NotificationCreator.messageTag(roomId, threadId = null)}"

    companion object {
        fun from(event: NotifiableMessageEvent): ConversationKey = ConversationKey(
            sessionId = event.sessionId,
            roomId = event.roomId,
        )
    }
}

private data class ActiveNotification(
    val sessionId: SessionId,
    val roomId: RoomId,
    val threadId: ThreadId?,
    val eventId: String,
    val eventIds: Set<String>,
)

private data class RenderedNotification(
    val notification: WatchMessageNotification,
    val activeNotification: ActiveNotification,
)

private fun WatchSyncEnvelope.withoutImagePreviewIfOversized(): WatchSyncEnvelope {
    if (WatchBridgeSerialization.encodeEnvelopeToBytes(this).size <= WatchProtocol.MAX_PAYLOAD_BYTES) {
        return this
    }
    val notificationPayload = payload as? WatchSync.MessageNotification ?: return this
    if (notificationPayload.notification.imagePreviewBytes == null) return this
    return copy(
        payload = notificationPayload.copy(
            notification = notificationPayload.notification.copy(imagePreviewBytes = null),
        ),
    )
}

private fun Context.loadNotificationImagePreview(event: NotifiableMessageEvent): ByteArray? {
    val imageUri = event.imageUri ?: return null
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(imageUri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight)
            }
            val bitmap = contentResolver.openInputStream(imageUri)?.use { input ->
                BitmapFactory.decodeStream(input, null, decodeOptions)
            }
            bitmap?.useForPreviewBytes()
        }
    }.getOrNull()
}

private fun calculateSampleSize(width: Int, height: Int): Int {
    var sampleSize = 1
    val maxDimension = maxOf(width, height)
    while (maxDimension / (sampleSize * 2) >= NOTIFICATION_IMAGE_MAX_DIMENSION_PX) {
        sampleSize *= 2
    }
    return sampleSize
}

private fun Bitmap.useForPreviewBytes(): ByteArray? {
    val scaledBitmap = scaleToNotificationBounds()
    return try {
        scaledBitmap.compressForNotification()
    } finally {
        if (scaledBitmap !== this) {
            scaledBitmap.recycle()
        }
        recycle()
    }
}

private fun Bitmap.scaleToNotificationBounds(): Bitmap {
    val maxDimension = maxOf(width, height)
    if (maxDimension <= NOTIFICATION_IMAGE_MAX_DIMENSION_PX) return this
    val scale = NOTIFICATION_IMAGE_MAX_DIMENSION_PX.toFloat() / maxDimension
    val scaledWidth = (width * scale).roundToInt().coerceAtLeast(1)
    val scaledHeight = (height * scale).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, scaledWidth, scaledHeight, true)
}

private fun Bitmap.compressForNotification(): ByteArray? {
    var quality = NOTIFICATION_IMAGE_INITIAL_QUALITY
    while (quality >= NOTIFICATION_IMAGE_MIN_QUALITY) {
        val bytes = ByteArrayOutputStream().use { output ->
            compress(Bitmap.CompressFormat.JPEG, quality, output)
            output.toByteArray()
        }
        if (bytes.size <= NOTIFICATION_IMAGE_MAX_BYTES) {
            return bytes
        }
        quality -= NOTIFICATION_IMAGE_QUALITY_STEP
    }
    return null
}

private fun String.compactNotificationLabelText(maxLength: Int): String {
    val normalized = trim().replace(NOTIFICATION_PREVIEW_WHITESPACE_REGEX, " ")
    if (normalized.length <= maxLength) return normalized
    return normalized.take(maxLength - 1).trimEnd() + "…"
}

private fun String.preserveNotificationMessageText(maxLength: Int): String {
    val normalized = trim()
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lines()
        .joinToString("\n") { line ->
            line.replace(NOTIFICATION_BODY_INLINE_WHITESPACE_REGEX, " ").trimEnd()
        }
        .replace(NOTIFICATION_BODY_EXCESSIVE_BLANK_LINES_REGEX, "\n\n")
        .trim()
    if (normalized.length <= maxLength) return normalized
    return normalized.take(maxLength - 1).trimEnd() + "…"
}
