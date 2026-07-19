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
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.media.MatrixMediaLoader
import io.element.android.libraries.matrix.api.media.MediaFile
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.media.MediaUploadHandler
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.EmbeddedEventInfo
import io.element.android.libraries.matrix.api.timeline.item.EventThreadInfo
import io.element.android.libraries.matrix.api.timeline.item.ThreadSummary
import io.element.android.libraries.matrix.api.timeline.item.event.AudioMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.EventOrTransactionId
import io.element.android.libraries.matrix.api.timeline.item.event.FormattedBody
import io.element.android.libraries.matrix.api.timeline.item.event.MessageFormat
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.VoiceMessageType
import io.element.android.libraries.matrix.api.user.MatrixUser
import io.element.android.libraries.matrix.test.AN_AVATAR_URL
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.A_USER_ID
import io.element.android.libraries.matrix.test.A_USER_NAME
import io.element.android.libraries.matrix.test.media.aMediaSource
import io.element.android.libraries.matrix.test.room.FakeBaseRoom
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.room.aRoomMember
import io.element.android.libraries.matrix.test.room.aRoomInfo
import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import io.element.android.libraries.matrix.test.timeline.aMessageContent
import io.element.android.libraries.matrix.test.timeline.aProfileDetails
import io.element.android.libraries.matrix.test.timeline.anEventTimelineItem
import io.element.android.watchbridge.contract.WatchBridgeSerialization
import io.element.android.watchbridge.contract.WatchProtocol
import io.element.android.watchbridge.contract.WatchSync
import io.element.android.watchbridge.contract.WatchSyncEnvelope
import io.element.android.watchbridge.contract.WatchVoiceDraft
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import kotlin.math.max

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ElementXWatchBridgeRuntimeTest {

    @Test
    fun `watch avatar projection prefers explicit room avatar over heroes`() {
        val roomAvatarUrl = "mxc://server/room-avatar"
        val hero = MatrixUser(A_USER_ID, A_USER_NAME, AN_AVATAR_URL)

        val result = aRoomInfo(avatarUrl = roomAvatarUrl, heroes = listOf(hero)).watchAvatarData()

        assertThat(result.url).isEqualTo(roomAvatarUrl)
    }

    @Test
    fun `watch avatar projection uses first hero when room avatar is missing`() {
        val hero = MatrixUser(A_USER_ID, A_USER_NAME, AN_AVATAR_URL)

        val result = aRoomInfo(avatarUrl = null, heroes = listOf(hero)).watchAvatarData()

        assertThat(result.id).isEqualTo(A_USER_ID.value)
        assertThat(result.url).isEqualTo(AN_AVATAR_URL)
    }

    @Test
    fun `watch DM avatar projection uses direct member image`() = runTest {
        val directAvatarUrl = "mxc://server/direct-member"
        val room = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(
                initialRoomInfo = aRoomInfo(isDm = true, avatarUrl = null, heroes = emptyList()),
                getDirectRoomMemberResult = {
                    aRoomMember(userId = A_USER_ID, displayName = A_USER_NAME, avatarUrl = directAvatarUrl)
                },
            ),
        )

        val result = room.watchAvatarData()

        assertThat(result.id).isEqualTo(A_USER_ID.value)
        assertThat(result.url).isEqualTo(directAvatarUrl)
    }

    @Test
    fun `watch avatar loading normalizes large source and fits data layer payload limit`() = runTest {
        val sourceBytes = createImageBytes(width = 2_048, height = 1_536)
        val mediaLoader = object : MatrixMediaLoader {
            override suspend fun loadMediaContent(source: MediaSource): Result<ByteArray> = Result.success(sourceBytes)

            override suspend fun loadMediaThumbnail(source: MediaSource, width: Long, height: Long): Result<ByteArray> =
                Result.failure(IllegalStateException("thumbnail unavailable"))

            override suspend fun downloadMediaFile(
                source: MediaSource,
                mimeType: String?,
                filename: String?,
                useCache: Boolean,
            ): Result<MediaFile> = error("unused in test")
        }

        val avatarBytes = requireNotNull(loadWatchAvatarBytes(mediaLoader, AN_AVATAR_URL).getOrThrow())
        val decoded = BitmapFactory.decodeByteArray(avatarBytes, 0, avatarBytes.size)
        val envelopeBytes = WatchBridgeSerialization.encodeEnvelopeToBytes(
            WatchSyncEnvelope(
                generatedAtMs = 1L,
                payload = WatchSync.AvatarUpdate(roomId = "!room:server", imageBytes = avatarBytes),
            )
        )

        assertThat(decoded).isNotNull()
        assertThat(max(decoded!!.width, decoded.height)).isAtMost(64)
        assertThat(envelopeBytes.size).isAtMost(WatchProtocol.MAX_PAYLOAD_BYTES)
    }

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
    fun `voice timeline projection does not expose Matrix media URL`() {
        val voiceEvent = MatrixTimelineItem.Event(
            uniqueId = UniqueId("voice"),
            event = anEventTimelineItem(
                eventId = EventId("\$voice:server"),
                timestamp = 1L,
                content = aMessageContent(
                    body = "Voice message",
                    messageType = VoiceMessageType(
                        filename = "voice.ogg",
                        caption = null,
                        formattedCaption = null,
                        source = MediaSource("mxc://server/media"),
                        info = null,
                        details = null,
                    ),
                ),
            ),
        )

        val projection = listOf(voiceEvent).toWatchTimelineProjection(
            roomId = "!room:server",
            limit = 20,
        )

        assertThat(projection.items.single().voiceMessageMeta?.audioUrl).isNull()
    }

    @Test
    fun `audio timeline projection carries playable audio metadata`() {
        val audioEvent = MatrixTimelineItem.Event(
            uniqueId = UniqueId("audio"),
            event = anEventTimelineItem(
                eventId = EventId("\$audio:server"),
                timestamp = 1L,
                content = aMessageContent(
                    body = "Audio message",
                    messageType = AudioMessageType(
                        filename = "audio.mp3",
                        caption = null,
                        formattedCaption = null,
                        source = MediaSource("mxc://server/audio"),
                        info = null,
                    ),
                ),
            ),
        )

        val projection = listOf(audioEvent).toWatchTimelineProjection(
            roomId = "!room:server",
            limit = 20,
        )

        val item = projection.items.single()
        assertThat(item.kind.name).isEqualTo("VOICE")
        assertThat(item.voiceMessageMeta).isNotNull()
        assertThat(item.voiceMessageMeta?.audioUrl).isNull()
    }

    @Test
    fun `watch voice message waits for upload handler before reporting success`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        lateinit var uploadHandler: RecordingMediaUploadHandler
        val timeline = FakeTimeline().apply {
            sendVoiceMessageLambda = { file, _, _, _ ->
                uploadHandler = RecordingMediaUploadHandler(file)
                Result.success(uploadHandler)
            }
        }
        val room = FakeJoinedRoom(liveTimeline = timeline)

        val result = sendWatchVoiceMessage(
            context = context,
            room = room,
            draft = aWatchVoiceDraft(),
            audioBytes = byteArrayOf(1, 2, 3, 4),
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(uploadHandler.awaitCalled).isTrue()
        assertThat(uploadHandler.fileExistedWhenAwaited).isTrue()
        assertThat(uploadHandler.file.exists()).isFalse()
    }

    @Test
    fun `watch voice message returns failure when upload handler fails`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uploadFailure = IllegalStateException("upload failed")
        lateinit var uploadHandler: RecordingMediaUploadHandler
        val timeline = FakeTimeline().apply {
            sendVoiceMessageLambda = { file, _, _, _ ->
                uploadHandler = RecordingMediaUploadHandler(file, Result.failure(uploadFailure))
                Result.success(uploadHandler)
            }
        }
        val room = FakeJoinedRoom(liveTimeline = timeline)

        val result = sendWatchVoiceMessage(
            context = context,
            room = room,
            draft = aWatchVoiceDraft(),
            audioBytes = byteArrayOf(1, 2, 3, 4),
        )

        assertThat(result.exceptionOrNull()).isSameInstanceAs(uploadFailure)
        assertThat(uploadHandler.awaitCalled).isTrue()
        assertThat(uploadHandler.file.exists()).isFalse()
    }

    @Test
    fun `timeline projection keeps full text and formatted body for detail rendering`() {
        val body = "Line\n".repeat(500)
        val formatted = "<strong>${"formatted ".repeat(200)}</strong>"
        val textEvent = MatrixTimelineItem.Event(
            uniqueId = UniqueId("text"),
            event = anEventTimelineItem(
                eventId = EventId("\$text:server"),
                timestamp = 1L,
                content = aMessageContent(
                    body = body,
                    messageType = TextMessageType(
                        body = body,
                        formatted = FormattedBody(MessageFormat.HTML, formatted),
                    ),
                ),
            ),
        )

        val projection = listOf(textEvent).toWatchTimelineProjection(
            roomId = "!room:server",
            limit = 20,
        )

        assertThat(projection.items.single().bodyText).isEqualTo(body)
        assertThat(projection.items.single().formattedText).isEqualTo(formatted)
    }

    @Test
    fun `thread projection keeps full text and formatted body for detail rendering`() {
        val body = "Thread detail\n".repeat(400)
        val formatted = "<em>${"thread formatted ".repeat(120)}</em>"
        val threadEvent = MatrixTimelineItem.Event(
            uniqueId = UniqueId("thread"),
            event = anEventTimelineItem(
                eventId = EventId("\$thread:server"),
                timestamp = 1L,
                content = aMessageContent(
                    body = body,
                    messageType = TextMessageType(
                        body = body,
                        formatted = FormattedBody(MessageFormat.HTML, formatted),
                    ),
                ),
            ),
        )

        val projection = listOf(threadEvent).toWatchThreadProjection(
            roomId = "!room:server",
            threadRootEventId = "\$root:server",
            limit = 20,
        )

        assertThat(projection.items.single().bodyText).isEqualTo(body)
        assertThat(projection.items.single().formattedText).isEqualTo(formatted)
    }

    @Test
    fun `watch preview text is compacted and bounded`() {
        val text = "Hello\n\n${"x".repeat(200)}"

        val compacted = text.compactWatchPreviewText(maxLength = 24)

        assertThat(compacted.length).isAtMost(24)
        assertThat(compacted).doesNotContain("\n")
        assertThat(compacted).endsWith("...")
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

    private fun aWatchVoiceDraft() = WatchVoiceDraft(
        draftId = "draft-id",
        roomId = A_ROOM_ID.value,
        tempAudioUri = "file://watch/voice.ogg",
        durationMs = 3_000L,
        mimeType = "audio/ogg",
        sampleRateHz = 16_000,
        channelCount = 1,
        sizeBytes = 4L,
        waveform = listOf(25, 50, 75),
    )

    private class RecordingMediaUploadHandler(
        val file: File,
        private val result: Result<Unit> = Result.success(Unit),
    ) : MediaUploadHandler {
        var awaitCalled = false
            private set
        var fileExistedWhenAwaited = false
            private set

        override suspend fun await(): Result<Unit> {
            awaitCalled = true
            fileExistedWhenAwaited = file.exists()
            return result
        }

        override fun cancel() = Unit
    }
}
