/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.watchbridge

import com.google.common.truth.Truth.assertThat
import io.element.android.watchbridge.contract.WatchAck
import io.element.android.watchbridge.contract.WatchCommand
import io.element.android.watchbridge.contract.WatchDataPaths
import io.element.android.watchbridge.contract.WatchFavoriteRoom
import io.element.android.watchbridge.contract.WatchMediaPreview
import io.element.android.watchbridge.contract.WatchPlaybackDescriptor
import io.element.android.watchbridge.contract.WatchRoomKind
import io.element.android.watchbridge.contract.WatchRoomSummary
import io.element.android.watchbridge.contract.WatchSync
import io.element.android.watchbridge.contract.WatchSyncEnvelope
import io.element.android.watchbridge.contract.WatchThreadItem
import io.element.android.watchbridge.contract.WatchTimelineItem
import io.element.android.watchbridge.contract.WatchVoiceDraft
import io.element.android.watchbridge.transport.WatchChannel
import io.element.android.watchbridge.transport.WatchTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.ArrayDeque

@OptIn(ExperimentalCoroutinesApi::class)
class WatchBridgeDispatcherTest {
    private open class RecordingTransport : WatchTransport {
        val publications = mutableListOf<Pair<String, WatchSyncEnvelope>>()
        val publicationUrgency = mutableListOf<Pair<String, Boolean>>()
        val messages = mutableListOf<Pair<String, WatchSyncEnvelope>>()
        override suspend fun publishSync(path: String, envelope: WatchSyncEnvelope, urgent: Boolean) {
            publications += path to envelope
            publicationUrgency += path to urgent
        }
        override suspend fun sendMessage(path: String, envelope: WatchSyncEnvelope): String {
            messages += path to envelope
            return "node"
        }
        override suspend fun deleteSync(path: String) {}
        override suspend fun openChannel(path: String): WatchChannel? = null
    }

    private class StubPort(
        private val favorites: List<WatchFavoriteRoom> = emptyList(),
        private val summary: WatchRoomSummary? = null,
        private val timeline: List<WatchTimelineItem> = emptyList(),
        private val thread: List<WatchThreadItem> = emptyList(),
        private val timelineFlow: Flow<List<WatchTimelineItem>>? = null,
        private val threadFlow: Flow<List<WatchThreadItem>>? = null,
        var sendTextResult: Result<String> = Result.success("\$ev"),
        var sendReactionResult: Result<Unit> = Result.success(Unit),
        var sendVoiceResult: Result<String> = Result.success(""),
        var roomMediaPreviewResult: Result<ByteArray?> = Result.success(null),
        val roomMediaPreviewResults: ArrayDeque<Result<ByteArray?>> = ArrayDeque(),
        val avatarThumbnailResults: ArrayDeque<Result<ByteArray?>> = ArrayDeque(),
    ) : ElementXWatchPort {
        var ensureLoadedCalls: MutableList<Int> = mutableListOf()
        val roomMediaPreviewCalls = mutableListOf<Pair<String, String>>()
        val voiceDraftCalls = mutableListOf<Pair<WatchVoiceDraft, ByteArray>>()
        val playbackDescriptorCalls = mutableListOf<PlaybackDescriptorCall>()
        val markAsReadCalls = mutableListOf<MarkAsReadCall>()
        override fun favorites(): Flow<List<WatchFavoriteRoom>> = flowOf(favorites)
        override suspend fun ensureRoomListLoaded(minimumCount: Int) {
            ensureLoadedCalls += minimumCount
        }
        override suspend fun roomSummary(roomId: String): WatchRoomSummary? = summary
        override fun roomTimeline(roomId: String, limit: Int): Flow<List<WatchTimelineItem>> = timelineFlow ?: flowOf(timeline)
        override fun threadTimeline(roomId: String, threadRootEventId: String, limit: Int) = threadFlow ?: flowOf(thread)
        override suspend fun roomMediaPreview(roomId: String, eventId: String): Result<ByteArray?> =
            if (roomMediaPreviewResults.isEmpty()) {
                roomMediaPreviewCalls += roomId to eventId
                roomMediaPreviewResult
            } else {
                roomMediaPreviewCalls += roomId to eventId
                roomMediaPreviewResults.removeFirst()
            }
        override suspend fun sendText(
            roomId: String,
            threadRootEventId: String?,
            inReplyToEventId: String?,
            text: String,
        ) = sendTextResult
        override suspend fun sendReaction(roomId: String, eventId: String, reactionKey: String) = sendReactionResult
        override suspend fun sendVoiceMessage(draft: WatchVoiceDraft, audioBytes: ByteArray): Result<String> {
            voiceDraftCalls += draft to audioBytes
            return sendVoiceResult
        }
        override suspend fun playbackDescriptor(roomId: String, eventId: String, threadRootEventId: String?): Result<WatchPlaybackDescriptor> {
            playbackDescriptorCalls += PlaybackDescriptorCall(roomId, eventId, threadRootEventId)
            return Result.success(
                WatchPlaybackDescriptor(
                    eventId = eventId,
                    roomId = roomId,
                    playbackUri = "content://phone/x",
                    durationMs = 1000L,
                    mimeType = "audio/ogg",
                ),
            )
        }
        override suspend fun roomAvatarThumbnail(roomId: String): Result<ByteArray?> =
            if (avatarThumbnailResults.isEmpty()) Result.success(null) else avatarThumbnailResults.removeFirst()
        override suspend fun markAsRead(roomId: String, eventId: String, threadRootEventId: String?): Result<Unit> {
            markAsReadCalls += MarkAsReadCall(roomId, eventId, threadRootEventId)
            return Result.success(Unit)
        }
    }

    private data class MarkAsReadCall(
        val roomId: String,
        val eventId: String,
        val threadRootEventId: String?,
    )

    private data class PlaybackDescriptorCall(
        val roomId: String,
        val eventId: String,
        val threadRootEventId: String?,
    )

    @Test
    fun `start publishes favorites snapshot`() = runTest(StandardTestDispatcher()) {
        val transport = RecordingTransport()
        val port = StubPort(
            favorites = listOf(
                WatchFavoriteRoom(roomId = "!a:s", displayName = "A", kind = WatchRoomKind.GROUP),
            ),
        )
        val dispatcher = WatchBridgeDispatcher(port, transport, this, clock = { 0L })

        dispatcher.start()
        advanceUntilIdle()

        val published = transport.publications.singleOrNull { it.first == WatchDataPaths.FAVORITES }
        checkNotNull(published) { "favorites snapshot not published" }
        val payload = published.second.payload as WatchSync.FavoritesSnapshot
        assertThat(payload.rooms).hasSize(1)
        assertThat(transport.publicationUrgency.single { it.first == WatchDataPaths.FAVORITES }.second).isFalse()
        assertThat(port.ensureLoadedCalls).isNotEmpty()
    }

    @Test
    fun `send text command acks sent with eventId`() = runTest(StandardTestDispatcher()) {
        val transport = RecordingTransport()
        val port = StubPort(sendTextResult = Result.success("\$ev:srv"))
        val dispatcher = WatchBridgeDispatcher(port, transport, this, clock = { 0L })

        val envelope = WatchSyncEnvelope(
            generatedAtMs = 0L,
            payload = WatchCommand.SendText(
                requestId = "r1",
                roomId = "!a:s",
                text = "hi",
                clientTsMs = 0L,
            ),
        )
        dispatcher.onEnvelope(envelope)
        advanceUntilIdle()

        val sentAck = transport.messages
            .map { it.second.payload }
            .filterIsInstance<WatchAck.Sent>()
            .singleOrNull { it.requestId == "r1" }
        assertThat(sentAck).isNotNull()
        assertThat(sentAck!!.eventId).isEqualTo("\$ev:srv")
    }

    @Test
    fun `duplicate requestId does not double-dispatch`() = runTest(StandardTestDispatcher()) {
        val transport = RecordingTransport()
        var calls = 0
        val port = object : ElementXWatchPort by StubPort() {
            override suspend fun sendText(
                roomId: String,
                threadRootEventId: String?,
                inReplyToEventId: String?,
                text: String,
            ): Result<String> {
                calls += 1
                return Result.success("\$ev")
            }
        }
        val dispatcher = WatchBridgeDispatcher(port, transport, this, clock = { 0L })

        val cmd = WatchSyncEnvelope(
            generatedAtMs = 0L,
            payload = WatchCommand.SendText(requestId = "dup", roomId = "!a:s", text = "hi", clientTsMs = 0L),
        )
        dispatcher.onEnvelope(cmd)
        dispatcher.onEnvelope(cmd) // retry with same requestId
        advanceUntilIdle()

        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun `duplicate requestId after completion replays terminal ack`() = runTest(StandardTestDispatcher()) {
        val transport = RecordingTransport()
        var calls = 0
        val port = object : ElementXWatchPort by StubPort() {
            override suspend fun sendText(
                roomId: String,
                threadRootEventId: String?,
                inReplyToEventId: String?,
                text: String,
            ): Result<String> {
                calls += 1
                return Result.success("\$ev")
            }
        }
        val dispatcher = WatchBridgeDispatcher(port, transport, this, clock = { 0L })

        val cmd = WatchSyncEnvelope(
            generatedAtMs = 0L,
            payload = WatchCommand.SendText(requestId = "replay", roomId = "!a:s", text = "hi", clientTsMs = 0L),
        )
        dispatcher.onEnvelope(cmd)
        advanceUntilIdle()
        dispatcher.onEnvelope(cmd)
        advanceUntilIdle()

        assertThat(calls).isEqualTo(1)
        assertThat(
            transport.messages
                .map { it.second.payload }
                .filterIsInstance<WatchAck.Sent>()
                .filter { it.requestId == "replay" },
        ).hasSize(2)
    }

    @Test
    fun `send text command forwards reply target`() = runTest(StandardTestDispatcher()) {
        val transport = RecordingTransport()
        var capturedReplyEventId: String? = null
        val port = object : ElementXWatchPort by StubPort() {
            override suspend fun sendText(
                roomId: String,
                threadRootEventId: String?,
                inReplyToEventId: String?,
                text: String,
            ): Result<String> {
                capturedReplyEventId = inReplyToEventId
                return Result.success("\$ev")
            }
        }
        val dispatcher = WatchBridgeDispatcher(port, transport, this, clock = { 0L })

        dispatcher.onEnvelope(
            WatchSyncEnvelope(
                generatedAtMs = 0L,
                payload = WatchCommand.SendText(
                    requestId = "reply-1",
                    roomId = "!a:s",
                    inReplyToEventId = "\$root:server",
                    text = "reply body",
                    clientTsMs = 0L,
                ),
            ),
        )
        advanceUntilIdle()

        assertThat(capturedReplyEventId).isEqualTo("\$root:server")
    }

    @Test
    fun `mark as read command delegates to port and returns sent ack`() = runTest(StandardTestDispatcher()) {
        val transport = RecordingTransport()
        val port = StubPort()
        val dispatcher = WatchBridgeDispatcher(port, transport, this, clock = { 0L })

        dispatcher.onEnvelope(
            WatchSyncEnvelope(
                generatedAtMs = 0L,
                payload = WatchCommand.MarkAsRead(
                    requestId = "mark-read-1",
                    roomId = "!a:s",
                    eventId = "event-1",
                    threadRootEventId = "root-1",
                ),
            ),
        )
        advanceUntilIdle()

        assertThat(port.markAsReadCalls).containsExactly(MarkAsReadCall("!a:s", "event-1", "root-1"))
        val sentAck = transport.messages
            .map { it.second.payload }
            .filterIsInstance<WatchAck.Sent>()
            .singleOrNull { it.requestId == "mark-read-1" }
        assertThat(sentAck).isNotNull()
    }

    @Test
    fun `request playback forwards thread root and returns descriptor`() = runTest(StandardTestDispatcher()) {
        val transport = RecordingTransport()
        val port = StubPort()
        val dispatcher = WatchBridgeDispatcher(port, transport, this, clock = { 0L })

        dispatcher.onEnvelope(
            WatchSyncEnvelope(
                generatedAtMs = 0L,
                payload = WatchCommand.RequestPlayback(
                    requestId = "playback-1",
                    roomId = "!a:s",
                    eventId = "\$voice:s",
                    threadRootEventId = "\$root:s",
                ),
            ),
        )
        advanceUntilIdle()

        assertThat(port.playbackDescriptorCalls).containsExactly(PlaybackDescriptorCall("!a:s", "\$voice:s", "\$root:s"))
        val ack = transport.messages
            .map { it.second.payload }
            .filterIsInstance<WatchAck.PlaybackReady>()
            .singleOrNull { it.requestId == "playback-1" }
        assertThat(ack?.descriptor?.eventId).isEqualTo("\$voice:s")
    }

    @Test
    fun `unsubscribe room command cancels active room projection`() = runTest(StandardTestDispatcher()) {
        val transport = RecordingTransport()
        var roomProjectionCancelled = false
        val port = StubPort(
            summary = WatchRoomSummary(
                roomId = "!a:s",
                displayName = "Room",
                kind = WatchRoomKind.GROUP,
                isEncrypted = false,
                canSendMessages = true,
                timelineVersion = 1L,
                lastSyncTsMs = 0L,
            ),
            timelineFlow = flow {
                try {
                    emit(emptyList())
                    awaitCancellation()
                } finally {
                    roomProjectionCancelled = true
                }
            },
        )
        val dispatcher = WatchBridgeDispatcher(port, transport, this, clock = { 0L })

        dispatcher.onEnvelope(
            WatchSyncEnvelope(
                generatedAtMs = 0L,
                payload = WatchCommand.OpenRoom(requestId = "open-room", roomId = "!a:s"),
            ),
        )
        runCurrent()
        dispatcher.onEnvelope(
            WatchSyncEnvelope(
                generatedAtMs = 0L,
                payload = WatchCommand.Unsubscribe(requestId = "unsubscribe-room", roomId = "!a:s"),
            ),
        )
        advanceUntilIdle()

        assertThat(roomProjectionCancelled).isTrue()
        val sentAck = transport.messages
            .map { it.second.payload }
            .filterIsInstance<WatchAck.Sent>()
            .singleOrNull { it.requestId == "unsubscribe-room" }
        assertThat(sentAck).isNotNull()
    }

    @Test
    fun `unsubscribe thread command cancels active thread projection`() = runTest(StandardTestDispatcher()) {
        val transport = RecordingTransport()
        var threadProjectionCancelled = false
        val port = StubPort(
            threadFlow = flow {
                try {
                    emit(emptyList())
                    awaitCancellation()
                } finally {
                    threadProjectionCancelled = true
                }
            },
        )
        val dispatcher = WatchBridgeDispatcher(port, transport, this, clock = { 0L })

        dispatcher.onEnvelope(
            WatchSyncEnvelope(
                generatedAtMs = 0L,
                payload = WatchCommand.FetchThread(requestId = "fetch-thread", roomId = "!a:s", threadRootEventId = "root"),
            ),
        )
        runCurrent()
        dispatcher.onEnvelope(
            WatchSyncEnvelope(
                generatedAtMs = 0L,
                payload = WatchCommand.Unsubscribe(requestId = "unsubscribe-thread", roomId = "!a:s", threadRootEventId = "root"),
            ),
        )
        advanceUntilIdle()

        assertThat(threadProjectionCancelled).isTrue()
        val sentAck = transport.messages
            .map { it.second.payload }
            .filterIsInstance<WatchAck.Sent>()
            .singleOrNull { it.requestId == "unsubscribe-thread" }
        assertThat(sentAck).isNotNull()
    }

    @Test
    fun `open room publish failure returns failed ack instead of crashing`() = runTest(StandardTestDispatcher()) {
        val transport = object : RecordingTransport() {
            override suspend fun publishSync(path: String, envelope: WatchSyncEnvelope, urgent: Boolean) {
                throw IllegalStateException("boom")
            }
        }
        val port = StubPort(
            summary = WatchRoomSummary(
                roomId = "!a:s",
                displayName = "Room",
                kind = WatchRoomKind.GROUP,
                isEncrypted = false,
                canSendMessages = true,
                timelineVersion = 1L,
                lastSyncTsMs = 0L,
            ),
        )
        val dispatcher = WatchBridgeDispatcher(port, transport, this, clock = { 0L })

        dispatcher.onEnvelope(
            WatchSyncEnvelope(
                generatedAtMs = 0L,
                payload = WatchCommand.OpenRoom(requestId = "r-open", roomId = "!a:s"),
            ),
        )
        advanceUntilIdle()

        val failedAck = transport.messages
            .map { it.second.payload }
            .filterIsInstance<WatchAck.Failed>()
            .singleOrNull { it.requestId == "r-open" }
        assertThat(failedAck).isNotNull()
    }

    @Test
    fun `fetch thread publish failure returns failed ack instead of crashing`() = runTest(StandardTestDispatcher()) {
        val transport = object : RecordingTransport() {
            override suspend fun publishSync(path: String, envelope: WatchSyncEnvelope, urgent: Boolean) {
                throw IllegalStateException("boom")
            }
        }
        val port = StubPort(
            thread = listOf(
                WatchThreadItem(
                    eventId = "ev",
                    threadRootEventId = "root",
                    roomId = "!a:s",
                    senderId = "@a:s",
                    senderDisplayName = "Alice",
                    timestampMs = 1L,
                    kind = io.element.android.watchbridge.contract.WatchTimelineItemKind.TEXT,
                    bodyText = "hello",
                ),
            ),
        )
        val dispatcher = WatchBridgeDispatcher(port, transport, this, clock = { 0L })

        dispatcher.onEnvelope(
            WatchSyncEnvelope(
                generatedAtMs = 0L,
                payload = WatchCommand.FetchThread(requestId = "r-thread", roomId = "!a:s", threadRootEventId = "root"),
            ),
        )
        advanceUntilIdle()

        val failedAck = transport.messages
            .map { it.second.payload }
            .filterIsInstance<WatchAck.Failed>()
            .singleOrNull { it.requestId == "r-thread" }
        assertThat(failedAck).isNotNull()
    }

    @Test
    fun `fetch thread waits for real timeline items before publishing without eager media preview`() = runTest(StandardTestDispatcher()) {
        val transport = RecordingTransport()
        val imageItem = WatchThreadItem(
            eventId = "image-1",
            threadRootEventId = "root",
            roomId = "!a:s",
            senderId = "@alice:s",
            senderDisplayName = "Alice",
            timestampMs = 2L,
            kind = io.element.android.watchbridge.contract.WatchTimelineItemKind.IMAGE,
            bodyText = "Photo",
            mediaPreview = WatchMediaPreview(widthPx = 200, heightPx = 200, mimeType = "image/jpeg"),
        )
        val port = StubPort(
            threadFlow = flow {
                delay(3_500L)
                emit(listOf(imageItem))
            },
            roomMediaPreviewResult = Result.success(byteArrayOf(8, 9, 10)),
        )
        val dispatcher = WatchBridgeDispatcher(port, transport, this, clock = { 100L })

        dispatcher.onEnvelope(
            WatchSyncEnvelope(
                generatedAtMs = 0L,
                payload = WatchCommand.FetchThread(requestId = "r-thread-media", roomId = "!a:s", threadRootEventId = "root"),
            ),
        )

        advanceTimeBy(3_000L)
        runCurrent()

        assertThat(
            transport.publications.none { (it.second.payload as? WatchSync.ThreadDelta)?.threadRootEventId == "root" },
        ).isTrue()

        advanceTimeBy(500L)
        runCurrent()
        advanceUntilIdle()

        val threadPublication = transport.publications
            .singleOrNull { it.first == WatchDataPaths.thread("!a:s", "root") }

        assertThat(threadPublication).isNotNull()
        assertThat((threadPublication!!.second.payload as WatchSync.ThreadDelta).items.map { it.eventId })
            .containsExactly("image-1")
        assertThat(transport.publications.none { it.first == WatchDataPaths.mediaPreview("!a:s", "image-1") }).isTrue()
        assertThat(port.roomMediaPreviewCalls).isEmpty()
    }

    @Test
    fun `avatar publication retries when thumbnail is temporarily unavailable`() = runTest(StandardTestDispatcher()) {
        val transport = RecordingTransport()
        val port = StubPort(
            favorites = listOf(
                WatchFavoriteRoom(
                    roomId = "!a:s",
                    displayName = "Alice",
                    avatarUri = "mxc://server/avatar",
                    kind = WatchRoomKind.DM,
                ),
            ),
            avatarThumbnailResults = ArrayDeque<Result<ByteArray?>>().apply {
                add(Result.success(null))
                add(Result.success(byteArrayOf(7, 8, 9)))
            },
        )
        val dispatcher = WatchBridgeDispatcher(port, transport, this, clock = { 0L })

        dispatcher.start()
        runCurrent()

        val initialAvatarPayload = transport.publications
            .map { it.second.payload }
            .filterIsInstance<WatchSync.AvatarUpdate>()
            .singleOrNull { it.roomId == "!a:s" }

        assertThat(initialAvatarPayload).isNotNull()
        assertThat(initialAvatarPayload!!.imageBytes).isNull()

        advanceTimeBy(1_500L)
        runCurrent()

        val avatarPayloads = transport.publications
            .map { it.second.payload }
            .filterIsInstance<WatchSync.AvatarUpdate>()
            .filter { it.roomId == "!a:s" }

        assertThat(avatarPayloads).hasSize(2)
        assertThat(avatarPayloads.last().imageBytes?.toList()).containsExactly(7.toByte(), 8.toByte(), 9.toByte()).inOrder()
        assertThat(transport.publicationUrgency.filter { it.first == WatchDataPaths.avatar("!a:s") }.map { it.second })
            .containsExactly(true, true)
            .inOrder()
    }

    @Test
    fun `dm avatar publication asks phone for thumbnail when room list has no avatar uri`() = runTest(StandardTestDispatcher()) {
        val transport = RecordingTransport()
        val port = StubPort(
            favorites = listOf(
                WatchFavoriteRoom(
                    roomId = "!dm:s",
                    displayName = "Alice",
                    avatarUri = null,
                    kind = WatchRoomKind.DM,
                ),
            ),
            avatarThumbnailResults = ArrayDeque<Result<ByteArray?>>().apply {
                add(Result.success(byteArrayOf(1, 2, 3)))
            },
        )
        val dispatcher = WatchBridgeDispatcher(port, transport, this, clock = { 0L })

        dispatcher.start()
        runCurrent()

        val avatarPayload = transport.publications
            .map { it.second.payload }
            .filterIsInstance<WatchSync.AvatarUpdate>()
            .single { it.roomId == "!dm:s" }
        assertThat(avatarPayload.imageBytes?.toList()).containsExactly(1.toByte(), 2.toByte(), 3.toByte()).inOrder()
        assertThat(transport.publicationUrgency.single { it.first == WatchDataPaths.avatar("!dm:s") }.second).isTrue()
    }

    @Test
    fun `refresh rooms urgently republishes favorites and avatars for a reset watch`() = runTest(StandardTestDispatcher()) {
        val roomId = "!dm:s"
        val transport = RecordingTransport()
        val port = StubPort(
            favorites = listOf(
                WatchFavoriteRoom(
                    roomId = roomId,
                    displayName = "Alice",
                    avatarUri = "mxc://server/avatar",
                    kind = WatchRoomKind.DM,
                ),
            ),
            avatarThumbnailResults = ArrayDeque<Result<ByteArray?>>().apply {
                add(Result.success(byteArrayOf(1, 2, 3)))
                add(Result.success(byteArrayOf(1, 2, 3)))
            },
        )
        val dispatcher = WatchBridgeDispatcher(port, transport, this, clock = { 0L })

        dispatcher.start()
        runCurrent()
        dispatcher.onEnvelope(
            WatchSyncEnvelope(
                generatedAtMs = 0L,
                payload = WatchCommand.RefreshRooms(requestId = "refresh", minimumCount = 30),
            ),
        )
        advanceUntilIdle()

        assertThat(transport.publications.count { it.first == WatchDataPaths.FAVORITES }).isEqualTo(2)
        assertThat(transport.publications.count { it.first == WatchDataPaths.avatar(roomId) }).isEqualTo(2)
        assertThat(transport.publicationUrgency.last { it.first == WatchDataPaths.FAVORITES }.second).isTrue()
        assertThat(transport.publicationUrgency.filter { it.first == WatchDataPaths.avatar(roomId) }.map { it.second })
            .containsExactly(true, true)
    }

    @Test
    fun `open room publishes timeline removed event ids without eager media preview`() = runTest(StandardTestDispatcher()) {
        val transport = RecordingTransport()
        val imageItem = WatchTimelineItem(
            eventId = "image-1",
            roomId = "!a:s",
            senderId = "@alice:s",
            senderDisplayName = "Alice",
            timestampMs = 2L,
            kind = io.element.android.watchbridge.contract.WatchTimelineItemKind.IMAGE,
            bodyText = "Photo",
            mediaPreview = WatchMediaPreview(widthPx = 200, heightPx = 200, mimeType = "image/jpeg"),
        )
        val textItem = WatchTimelineItem(
            eventId = "text-1",
            roomId = "!a:s",
            senderId = "@bob:s",
            senderDisplayName = "Bob",
            timestampMs = 1L,
            kind = io.element.android.watchbridge.contract.WatchTimelineItemKind.TEXT,
            bodyText = "Hello",
        )
        val port = StubPort(
            summary = WatchRoomSummary(
                roomId = "!a:s",
                displayName = "Room",
                kind = WatchRoomKind.GROUP,
                isEncrypted = false,
                canSendMessages = true,
                timelineVersion = 5L,
                lastSyncTsMs = 0L,
            ),
            timelineFlow = flowOf(
                listOf(textItem, imageItem),
                listOf(imageItem.copy(isReadMarkerAnchor = true)),
            ),
            roomMediaPreviewResult = Result.success(byteArrayOf(4, 5, 6)),
        )
        val dispatcher = WatchBridgeDispatcher(port, transport, this, clock = { 100L })

        dispatcher.onEnvelope(
            WatchSyncEnvelope(
                generatedAtMs = 0L,
                payload = WatchCommand.OpenRoom(requestId = "open-media", roomId = "!a:s"),
            ),
        )
        advanceUntilIdle()

        val secondTimelineDelta = transport.publications
            .map { it.second.payload }
            .filterIsInstance<WatchSync.TimelineDelta>()
            .last()

        assertThat(transport.publications.none { it.first == WatchDataPaths.mediaPreview("!a:s", "image-1") }).isTrue()
        assertThat(port.roomMediaPreviewCalls).isEmpty()
        assertThat(secondTimelineDelta.removedEventIds).containsExactly("text-1")
    }

    @Test
    fun `request media preview retries publication after initial failure`() = runTest(StandardTestDispatcher()) {
        val transport = RecordingTransport()
        val port = StubPort(
            roomMediaPreviewResults = ArrayDeque<Result<ByteArray?>>().apply {
                add(Result.failure(IllegalStateException("temporary failure")))
                add(Result.success(byteArrayOf(9, 8, 7)))
            },
        )
        val dispatcher = WatchBridgeDispatcher(port, transport, this, clock = { 100L })

        dispatcher.onEnvelope(
            WatchSyncEnvelope(
                generatedAtMs = 0L,
                payload = WatchCommand.RequestMediaPreview(
                    requestId = "preview-retry",
                    roomId = "!a:s",
                    eventId = "image-1",
                ),
            ),
        )
        advanceTimeBy(1_000L)
        runCurrent()
        advanceUntilIdle()

        val mediaPublication = transport.publications
            .singleOrNull { it.first == WatchDataPaths.mediaPreview("!a:s", "image-1") }

        assertThat(mediaPublication).isNotNull()
        assertThat((mediaPublication!!.second.payload as WatchSync.MediaPreview).imageBytes?.toList())
            .containsExactly(9.toByte(), 8.toByte(), 7.toByte())
            .inOrder()
        assertThat(port.roomMediaPreviewCalls).containsExactly("!a:s" to "image-1", "!a:s" to "image-1").inOrder()
    }

    @Test
    fun `request media preview publishes preview payload and payload-ready ack`() = runTest(StandardTestDispatcher()) {
        val transport = RecordingTransport()
        val port = StubPort(
            roomMediaPreviewResults = ArrayDeque<Result<ByteArray?>>().apply {
                add(Result.success(null))
                add(Result.success(byteArrayOf(3, 2, 1)))
            },
        )
        val dispatcher = WatchBridgeDispatcher(port, transport, this, clock = { 100L })

        dispatcher.onEnvelope(
            WatchSyncEnvelope(
                generatedAtMs = 0L,
                payload = WatchCommand.RequestMediaPreview(
                    requestId = "preview-1",
                    roomId = "!a:s",
                    eventId = "image-1",
                ),
            ),
        )

        advanceTimeBy(1_000L)
        runCurrent()
        advanceUntilIdle()

        val mediaPublication = transport.publications
            .singleOrNull { it.first == WatchDataPaths.mediaPreview("!a:s", "image-1") }
        val payloadReadyAck = transport.messages
            .map { it.second.payload }
            .filterIsInstance<WatchAck.PayloadReady>()
            .singleOrNull { it.requestId == "preview-1" }

        assertThat(mediaPublication).isNotNull()
        assertThat((mediaPublication!!.second.payload as WatchSync.MediaPreview).imageBytes?.toList())
            .containsExactly(3.toByte(), 2.toByte(), 1.toByte())
            .inOrder()
        assertThat(payloadReadyAck).isNotNull()
        assertThat(payloadReadyAck!!.dataPath).isEqualTo(WatchDataPaths.mediaPreview("!a:s", "image-1"))
    }

    @Test
    fun `open room does not materialize media previews until requested`() = runTest(StandardTestDispatcher()) {
        val transport = RecordingTransport()
        val mediaItems = (1..8).map { index ->
            WatchTimelineItem(
                eventId = "image-$index",
                roomId = "!a:s",
                senderId = "@alice:s",
                senderDisplayName = "Alice",
                timestampMs = index.toLong(),
                kind = io.element.android.watchbridge.contract.WatchTimelineItemKind.IMAGE,
                bodyText = "Photo $index",
                mediaPreview = WatchMediaPreview(widthPx = 200, heightPx = 200, mimeType = "image/jpeg"),
            )
        }
        val port = StubPort(
            summary = WatchRoomSummary(
                roomId = "!a:s",
                displayName = "Room",
                kind = WatchRoomKind.GROUP,
                isEncrypted = false,
                canSendMessages = true,
                timelineVersion = 5L,
                lastSyncTsMs = 0L,
            ),
            timelineFlow = flowOf(mediaItems),
            roomMediaPreviewResult = Result.success(byteArrayOf(1, 2, 3)),
        )
        val dispatcher = WatchBridgeDispatcher(port, transport, this, clock = { 100L })

        dispatcher.onEnvelope(
            WatchSyncEnvelope(
                generatedAtMs = 0L,
                payload = WatchCommand.OpenRoom(requestId = "open-preview-window", roomId = "!a:s"),
            ),
        )
        advanceUntilIdle()

        val previewEventIds = transport.publications
            .mapNotNull { (_, envelope) -> (envelope.payload as? WatchSync.MediaPreview)?.eventId }
        val timelineEventIds = transport.publications
            .mapNotNull { (_, envelope) -> (envelope.payload as? WatchSync.TimelineDelta)?.items?.map { it.eventId } }
            .last()

        assertThat(previewEventIds).isEmpty()
        assertThat(timelineEventIds).containsExactlyElementsIn(mediaItems.map { it.eventId }).inOrder()
        assertThat(port.roomMediaPreviewCalls).isEmpty()
    }

    @Test
    fun `voice draft completes when command arrives before audio`() = runTest(StandardTestDispatcher()) {
        val transport = RecordingTransport()
        val port = StubPort(sendVoiceResult = Result.success(""))
        val dispatcher = WatchBridgeDispatcher(port, transport, this, clock = { 0L })
        val draft = WatchVoiceDraft(
            draftId = "draft-1",
            roomId = "!a:s",
            tempAudioUri = "file:///tmp/draft-1.ogg",
            durationMs = 1_000L,
            mimeType = "audio/ogg",
            sampleRateHz = 16_000,
            channelCount = 1,
            sizeBytes = 3L,
        )

        dispatcher.onEnvelope(
            WatchSyncEnvelope(
                generatedAtMs = 0L,
                payload = WatchCommand.UploadVoiceDraft(requestId = "voice-1", draft = draft),
            ),
        )
        dispatcher.onVoiceDraftAudio("draft-1", byteArrayOf(1, 2, 3))
        advanceUntilIdle()

        assertThat(port.voiceDraftCalls).hasSize(1)
        assertThat(port.voiceDraftCalls.single().first).isEqualTo(draft)
        assertThat(port.voiceDraftCalls.single().second.toList()).containsExactly(1.toByte(), 2.toByte(), 3.toByte()).inOrder()

        val pendingAck = transport.messages
            .map { it.second.payload }
            .filterIsInstance<WatchAck.Pending>()
            .singleOrNull { it.requestId == "voice-1" }
        val sentAck = transport.messages
            .map { it.second.payload }
            .filterIsInstance<WatchAck.Sent>()
            .singleOrNull { it.requestId == "voice-1" }

        assertThat(pendingAck).isNotNull()
        assertThat(sentAck).isNotNull()
        assertThat(sentAck!!.eventId).isNull()
    }

    @Test
    fun `voice draft completes when audio arrives before command`() = runTest(StandardTestDispatcher()) {
        val transport = RecordingTransport()
        val port = StubPort(sendVoiceResult = Result.success(""))
        val dispatcher = WatchBridgeDispatcher(port, transport, this, clock = { 0L })
        val draft = WatchVoiceDraft(
            draftId = "draft-2",
            roomId = "!a:s",
            tempAudioUri = "file:///tmp/draft-2.ogg",
            durationMs = 1_200L,
            mimeType = "audio/ogg",
            sampleRateHz = 16_000,
            channelCount = 1,
            sizeBytes = 4L,
        )

        dispatcher.onVoiceDraftAudio("draft-2", byteArrayOf(4, 5, 6, 7))
        dispatcher.onEnvelope(
            WatchSyncEnvelope(
                generatedAtMs = 0L,
                payload = WatchCommand.UploadVoiceDraft(requestId = "voice-2", draft = draft),
            ),
        )
        advanceUntilIdle()

        assertThat(port.voiceDraftCalls).hasSize(1)
        assertThat(port.voiceDraftCalls.single().first).isEqualTo(draft)
        assertThat(port.voiceDraftCalls.single().second.toList()).containsExactly(4.toByte(), 5.toByte(), 6.toByte(), 7.toByte()).inOrder()
    }
}
