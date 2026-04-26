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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WatchBridgeDispatcherTest {

    private class RecordingTransport : WatchTransport {
        val publications = mutableListOf<Pair<String, WatchSyncEnvelope>>()
        val messages = mutableListOf<Pair<String, WatchSyncEnvelope>>()
        override suspend fun publishSync(path: String, envelope: WatchSyncEnvelope) {
            publications += path to envelope
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
        var sendTextResult: Result<String> = Result.success("\$ev"),
        var sendReactionResult: Result<Unit> = Result.success(Unit),
    ) : ElementXWatchPort {
        var ensureLoadedCalls: MutableList<Int> = mutableListOf()
        override fun favorites(): Flow<List<WatchFavoriteRoom>> = flowOf(favorites)
        override suspend fun ensureRoomListLoaded(minimumCount: Int) {
            ensureLoadedCalls += minimumCount
        }
        override suspend fun roomSummary(roomId: String): WatchRoomSummary? = summary
        override fun roomTimeline(roomId: String, limit: Int): Flow<List<WatchTimelineItem>> = flowOf(timeline)
        override fun threadTimeline(roomId: String, threadRootEventId: String, limit: Int) = flowOf(thread)
        override suspend fun sendText(roomId: String, threadRootEventId: String?, text: String) = sendTextResult
        override suspend fun sendReaction(roomId: String, eventId: String, reactionKey: String) = sendReactionResult
        override suspend fun sendVoiceMessage(draft: WatchVoiceDraft, audioBytes: ByteArray) = Result.success("\$v")
        override suspend fun playbackDescriptor(roomId: String, eventId: String) =
            Result.success(
                WatchPlaybackDescriptor(
                    eventId = eventId,
                    roomId = roomId,
                    playbackUri = "content://phone/x",
                    durationMs = 1000L,
                    mimeType = "audio/ogg",
                ),
            )
        override suspend fun markAsRead(roomId: String, eventId: String) = Result.success(Unit)
    }

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
            override suspend fun sendText(roomId: String, threadRootEventId: String?, text: String): Result<String> {
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
}
