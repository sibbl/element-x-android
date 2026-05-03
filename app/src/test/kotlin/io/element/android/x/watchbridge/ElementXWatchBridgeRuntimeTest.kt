/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.watchbridge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.media.MatrixMediaLoader
import io.element.android.libraries.matrix.api.media.MediaFile
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.EmbeddedEventInfo
import io.element.android.libraries.matrix.api.timeline.item.EventThreadInfo
import io.element.android.libraries.matrix.api.timeline.item.ThreadSummary
import io.element.android.libraries.matrix.api.timeline.item.event.EventOrTransactionId
import io.element.android.libraries.matrix.test.media.aMediaSource
import io.element.android.libraries.matrix.test.timeline.aMessageContent
import io.element.android.libraries.matrix.test.timeline.aProfileDetails
import io.element.android.libraries.matrix.test.timeline.anEventTimelineItem
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.URL
import kotlin.math.max

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ElementXWatchBridgeRuntimeTest {

    @Test
    fun `thread responses are excluded from room timeline projection`() {
        val threadRootId = EventId("\$root:server")
        val threadReplyId = EventId("\$reply:server")
        val latestReplyContent = aMessageContent(body = "Nested reply")

        val threadRoot = MatrixTimelineItem.Event(
            uniqueId = UniqueId("root"),
            event = anEventTimelineItem(
                eventId = threadRootId,
                timestamp = 1L,
                content = aMessageContent(
                    body = "Thread root",
                    threadInfo = EventThreadInfo.ThreadRoot(
                        summary = ThreadSummary(
                            latestEvent = AsyncData.Success(
                                EmbeddedEventInfo(
                                    eventOrTransactionId = EventOrTransactionId.Event(threadReplyId),
                                    content = latestReplyContent,
                                    senderId = UserId("@alice:server"),
                                    senderProfile = aProfileDetails(displayName = "Alice"),
                                    timestamp = 2L,
                                ),
                            ),
                            numberOfReplies = 3L,
                        ),
                    ),
                ),
            ),
        )
        val threadReply = MatrixTimelineItem.Event(
            uniqueId = UniqueId("reply"),
            event = anEventTimelineItem(
                eventId = threadReplyId,
                timestamp = 2L,
                content = aMessageContent(
                    body = "Nested reply",
                    threadInfo = EventThreadInfo.ThreadResponse(ThreadId(threadRootId.value)),
                ),
            ),
        )

        val projection = listOf(threadRoot, threadReply).toWatchTimelineProjection(
            roomId = "!room:server",
            limit = 20,
        )

        assertThat(projection.items.map { it.eventId }).containsExactly(threadRootId.value)
        assertThat(projection.items.single().hasThread).isTrue()
        assertThat(projection.items.single().threadReplyCount).isEqualTo(3)
    }

    @Test
    fun `media preview loading falls back to mocked content endpoint when thumbnail fetch fails`() = runTest {
        val imageBytes = createImageBytes(width = 1200, height = 900)
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "image/jpeg")
                    .setBody(Buffer().write(imageBytes)),
            )
            val mediaLoader = object : MatrixMediaLoader {
                override suspend fun loadMediaContent(source: MediaSource): Result<ByteArray> = runCatching {
                    URL(source.safeUrl).openStream().use { it.readBytes() }
                }

                override suspend fun loadMediaThumbnail(source: MediaSource, width: Long, height: Long): Result<ByteArray> =
                    Result.failure(IllegalStateException("thumbnail unavailable"))

                override suspend fun downloadMediaFile(
                    source: MediaSource,
                    mimeType: String?,
                    filename: String?,
                    useCache: Boolean,
                ): Result<MediaFile> = error("unused in test")
            }

            val result = loadWatchMediaPreviewBytes(
                mediaLoader = mediaLoader,
                sourceRef = MediaPreviewSourceRef(
                    primarySource = aMediaSource(server.url("/_matrix/media/v3/download/server/media").toString()),
                ),
                maxDimensionPx = 384,
            )

            val previewBytes = requireNotNull(result.getOrThrow())
            val previewBitmap = BitmapFactory.decodeByteArray(previewBytes, 0, previewBytes.size)

            assertThat(previewBitmap).isNotNull()
            assertThat(max(previewBitmap!!.width, previewBitmap.height)).isAtMost(384)
            assertThat(server.takeRequest().path).isEqualTo("/_matrix/media/v3/download/server/media")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `media preview loading prefers thumbnail source content before generating thumbnail`() = runTest {
        val thumbnailBytes = createImageBytes(width = 128, height = 96)
        val fullBytes = createImageBytes(width = 1200, height = 900)
        val mediaLoader = object : MatrixMediaLoader {
            var requestedThumbnailCount = 0
            val loadedSources = mutableListOf<String>()

            override suspend fun loadMediaContent(source: MediaSource): Result<ByteArray> = runCatching {
                loadedSources += source.safeUrl
                when (source.safeUrl) {
                    "mxc://server/thumb" -> thumbnailBytes
                    "mxc://server/full" -> fullBytes
                    else -> error("unexpected source ${source.safeUrl}")
                }
            }

            override suspend fun loadMediaThumbnail(source: MediaSource, width: Long, height: Long): Result<ByteArray> {
                requestedThumbnailCount += 1
                return Result.failure(IllegalStateException("should not generate thumbnail when thumbnail source content exists"))
            }

            override suspend fun downloadMediaFile(
                source: MediaSource,
                mimeType: String?,
                filename: String?,
                useCache: Boolean,
            ): Result<MediaFile> = error("unused in test")
        }

        val result = loadWatchMediaPreviewBytes(
            mediaLoader = mediaLoader,
            sourceRef = MediaPreviewSourceRef(
                primarySource = aMediaSource("mxc://server/full"),
                thumbnailSource = aMediaSource("mxc://server/thumb"),
            ),
            maxDimensionPx = 384,
        )

        val previewBytes = requireNotNull(result.getOrThrow())
        val previewBitmap = BitmapFactory.decodeByteArray(previewBytes, 0, previewBytes.size)

        assertThat(previewBitmap).isNotNull()
        assertThat(previewBitmap!!.width).isEqualTo(128)
        assertThat(previewBitmap.height).isEqualTo(96)
        assertThat(mediaLoader.loadedSources).containsExactly("mxc://server/thumb")
        assertThat(mediaLoader.requestedThumbnailCount).isEqualTo(0)
    }

    private fun createImageBytes(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.CYAN)
        }
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
            output.toByteArray()
        }
    }
}
