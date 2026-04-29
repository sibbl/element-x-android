/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.bridge

import com.google.common.truth.Truth.assertThat
import io.element.android.watchbridge.contract.WatchBridgeSerialization
import io.element.android.watchbridge.contract.WatchRoomKind
import io.element.android.watchbridge.contract.WatchSync
import io.element.android.watchbridge.contract.WatchSyncEnvelope
import io.element.android.watchbridge.contract.WatchTimelineItem
import io.element.android.watchbridge.contract.WatchTimelineItemKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

@OptIn(ExperimentalCoroutinesApi::class)
class WearBridgeCacheStoreTest {

    @Test
    fun `persisted envelopes restore with timeline and avatar data`() = runTest {
        val rootDir = tempDir()
        val store = WearBridgeCacheStore(rootDir)

        store.persist(
            WatchSyncEnvelope(
                generatedAtMs = 1L,
                payload = WatchSync.FavoritesSnapshot(
                    rooms = listOf(
                        io.element.android.watchbridge.contract.WatchFavoriteRoom(
                            roomId = "!room:server",
                            displayName = "Alice",
                            avatarUri = "mxc://server/avatar",
                            kind = WatchRoomKind.DM,
                        ),
                    ),
                ),
            ),
        )
        store.persist(
            WatchSyncEnvelope(
                generatedAtMs = 2L,
                payload = WatchSync.RoomSummary(
                    summary = io.element.android.watchbridge.contract.WatchRoomSummary(
                        roomId = "!room:server",
                        displayName = "Alice",
                        avatarUri = "mxc://server/avatar",
                        kind = WatchRoomKind.DM,
                        isEncrypted = true,
                        canSendMessages = true,
                        timelineVersion = 7L,
                        lastSyncTsMs = 123L,
                    ),
                ),
            ),
        )
        store.persist(
            WatchSyncEnvelope(
                generatedAtMs = 3L,
                payload = WatchSync.TimelineDelta(
                    roomId = "!room:server",
                    fromTimelineVersion = 6L,
                    toTimelineVersion = 7L,
                    items = listOf(
                        WatchTimelineItem(
                            eventId = "\$event:server",
                            roomId = "!room:server",
                            senderId = "@alice:server",
                            senderDisplayName = "Alice",
                            timestampMs = 10L,
                            kind = WatchTimelineItemKind.TEXT,
                            bodyText = "Hello from cache",
                        ),
                    ),
                ),
            ),
        )
        store.persist(
            WatchSyncEnvelope(
                generatedAtMs = 4L,
                payload = WatchSync.AvatarUpdate(
                    roomId = "!room:server",
                    imageBytes = byteArrayOf(1, 2, 3, 4),
                ),
            ),
        )

        val restored = WearBridgeCacheStore(rootDir).restoreEnvelopes()
        val favorites = restored.first { it.payload is WatchSync.FavoritesSnapshot }.payload as WatchSync.FavoritesSnapshot
        val timeline = restored.first { it.payload is WatchSync.TimelineDelta }.payload as WatchSync.TimelineDelta
        val avatarBytes = WearBridgeCacheStore(rootDir).readAvatar("!room:server")

        assertThat(favorites.rooms).hasSize(1)
        assertThat(timeline.items.single().bodyText).isEqualTo("Hello from cache")
        assertThat(avatarBytes).isNotNull()
        assertThat(avatarBytes!!.toList()).containsExactly(1.toByte(), 2.toByte(), 3.toByte(), 4.toByte()).inOrder()
    }

    @Test
    fun `room invalidation removes room timeline and avatar artifacts`() = runTest {
        val rootDir = tempDir()
        val store = WearBridgeCacheStore(rootDir)

        store.persist(
            WatchSyncEnvelope(
                generatedAtMs = 1L,
                payload = WatchSync.TimelineDelta(
                    roomId = "!room:server",
                    fromTimelineVersion = 0L,
                    toTimelineVersion = 1L,
                    items = emptyList(),
                ),
            ),
        )
        store.persist(
            WatchSyncEnvelope(
                generatedAtMs = 2L,
                payload = WatchSync.AvatarUpdate(
                    roomId = "!room:server",
                    imageBytes = byteArrayOf(9, 9, 9),
                ),
            ),
        )
        store.persist(
            WatchSyncEnvelope(
                generatedAtMs = 3L,
                payload = WatchSync.Invalidation(
                    scope = WatchSync.Invalidation.InvalidationScope.ROOM,
                    roomId = "!room:server",
                ),
            ),
        )

        val restored = store.restoreEnvelopes()
        assertThat(restored.any { (it.payload as? WatchSync.TimelineDelta)?.roomId == "!room:server" }).isFalse()
        assertThat(store.readAvatar("!room:server")).isNull()
    }

    @Test
    fun `media preview persists restores and reads back`() = runTest {
        val rootDir = tempDir()
        val store = WearBridgeCacheStore(rootDir = rootDir, clock = { 1_000L })

        store.persist(
            WatchSyncEnvelope(
                generatedAtMs = 10L,
                expiresAtMs = 8_000L,
                payload = WatchSync.MediaPreview(
                    roomId = "!room:server",
                    eventId = "\$image:server",
                    imageBytes = byteArrayOf(7, 8, 9),
                ),
            ),
        )

        val restored = store.restoreEnvelopes()
        val mediaPayload = restored.first { it.payload is WatchSync.MediaPreview }.payload as WatchSync.MediaPreview

        assertThat(mediaPayload.eventId).isEqualTo("\$image:server")
        assertThat(store.readMediaPreview("!room:server", "\$image:server")?.toList())
            .containsExactly(7.toByte(), 8.toByte(), 9.toByte())
            .inOrder()
    }

    @Test
    fun `timeline removal deletes cached media preview`() = runTest {
        val rootDir = tempDir()
        val store = WearBridgeCacheStore(rootDir = rootDir, clock = { 1_000L })

        store.persist(
            WatchSyncEnvelope(
                generatedAtMs = 1L,
                expiresAtMs = 10_000L,
                payload = WatchSync.MediaPreview(
                    roomId = "!room:server",
                    eventId = "\$image:server",
                    imageBytes = byteArrayOf(1, 2, 3),
                ),
            ),
        )
        store.persist(
            WatchSyncEnvelope(
                generatedAtMs = 2L,
                payload = WatchSync.TimelineDelta(
                    roomId = "!room:server",
                    fromTimelineVersion = 0L,
                    toTimelineVersion = 1L,
                    items = emptyList(),
                    removedEventIds = listOf("\$image:server"),
                ),
            ),
        )

        assertThat(store.readMediaPreview("!room:server", "\$image:server")).isNull()
    }

    @Test
    fun `expired media preview is pruned on restore`() = runTest {
        val rootDir = tempDir()
        val store = WearBridgeCacheStore(rootDir = rootDir, clock = { 1_000L })

        store.persist(
            WatchSyncEnvelope(
                generatedAtMs = 1L,
                expiresAtMs = 500L,
                payload = WatchSync.MediaPreview(
                    roomId = "!room:server",
                    eventId = "expired",
                    imageBytes = ByteArray(24) { 1 },
                ),
            ),
        )

        assertThat(store.restoreEnvelopes().any { (it.payload as? WatchSync.MediaPreview)?.eventId == "expired" }).isFalse()
        assertThat(store.readMediaPreview("!room:server", "expired")).isNull()
    }

    @Test
    fun `media quota evicts the oldest preview`() = runTest {
        val rootDir = tempDir()
        val first = WatchSyncEnvelope(
            generatedAtMs = 1L,
            expiresAtMs = 10_000L,
            payload = WatchSync.MediaPreview(
                roomId = "!room:server",
                eventId = "first",
                imageBytes = ByteArray(24) { 1 },
            ),
        )
        val second = WatchSyncEnvelope(
            generatedAtMs = 2L,
            expiresAtMs = 10_000L,
            payload = WatchSync.MediaPreview(
                roomId = "!room:server",
                eventId = "second",
                imageBytes = ByteArray(24) { 2 },
            ),
        )
        val quota = WatchBridgeSerialization.encodeEnvelopeToBytes(second).size.toLong() + 8L
        val store = WearBridgeCacheStore(
            rootDir = rootDir,
            clock = { 1_000L },
            maxMediaCacheBytes = quota,
        )

        store.persist(first)
        store.persist(second)

        assertThat(store.restoreEnvelopes().mapNotNull { (it.payload as? WatchSync.MediaPreview)?.eventId })
            .containsExactly("second")
        assertThat(store.readMediaPreview("!room:server", "first")).isNull()
        assertThat(store.readMediaPreview("!room:server", "second")).isNotNull()
    }

    private fun tempDir(): File = createTempDirectory(prefix = "wear-bridge-cache-test").toFile()
}
