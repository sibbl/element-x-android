/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui.room

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.element.android.watchbridge.contract.WatchMediaPreview
import io.element.android.watchbridge.contract.WatchReactionSummary
import io.element.android.watchbridge.contract.WatchTimelineItem
import io.element.android.watchbridge.contract.WatchTimelineItemKind
import io.element.android.watchbridge.contract.WatchVoiceMeta
import io.element.android.wearapp.R
import io.element.android.wearapp.ui.theme.WearAppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class TimelineItemViewTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `timeline image row shows unavailable placeholder after preview timeout when bytes are missing`() {
        val unavailableText = rule.activity.getString(R.string.screen_media_preview_unavailable)
        rule.mainClock.autoAdvance = false

        try {
            rule.setContent {
                WearAppTheme {
                    TimelineMessageRow(
                        item = anImageTimelineItem(),
                        mediaPreviewBytes = null,
                        onClick = {},
                        onOpenThread = null,
                    )
                }
            }

            rule.onNodeWithText(unavailableText).assertDoesNotExist()

            rule.mainClock.advanceTimeBy(4_100L)
            rule.waitForIdle()

            rule.onNodeWithText(unavailableText).assertExists()
            rule.onNodeWithText("Vacation photo").assertExists()
        } finally {
            rule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun `timeline image row hides unavailable placeholder when preview bytes are present`() {
        val unavailableText = rule.activity.getString(R.string.screen_media_preview_unavailable)
        val previewBytes = createPreviewBytes()

        rule.setContent {
            WearAppTheme {
                TimelineMessageRow(
                    item = anImageTimelineItem(),
                    mediaPreviewBytes = previewBytes,
                    onClick = {},
                    onOpenThread = null,
                )
            }
        }

        rule.onNodeWithText("Vacation photo").assertExists()
        rule.onNodeWithText(unavailableText).assertDoesNotExist()
        assertThat(previewBytes).isNotEmpty()
    }

    @Test
    fun `timeline image row delays unavailable placeholder while preview is still expected`() {
        val unavailableText = rule.activity.getString(R.string.screen_media_preview_unavailable)
        rule.mainClock.autoAdvance = false

        try {
            rule.setContent {
                WearAppTheme {
                    TimelineMessageRow(
                        item = anImageTimelineItem().copy(
                            mediaPreview = WatchMediaPreview(widthPx = 128, heightPx = 128, mimeType = "image/png"),
                        ),
                        mediaPreviewBytes = null,
                        onClick = {},
                        onOpenThread = null,
                    )
                }
            }

            rule.onNodeWithText(unavailableText).assertDoesNotExist()

            rule.mainClock.advanceTimeBy(3_500L)
            rule.waitForIdle()

            rule.onNodeWithText(unavailableText).assertDoesNotExist()

            rule.mainClock.advanceTimeBy(600L)
            rule.waitForIdle()

            rule.onNodeWithText(unavailableText).assertExists()
        } finally {
            rule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun `timeline image row requests preview when bytes are missing`() {
        var requestCount = 0

        rule.setContent {
            WearAppTheme {
                TimelineMessageRow(
                    item = anImageTimelineItem(),
                    mediaPreviewBytes = null,
                    onRequestMediaPreview = { requestCount += 1 },
                    onClick = {},
                    onOpenThread = null,
                )
            }
        }

        rule.runOnIdle {
            assertThat(requestCount).isEqualTo(1)
        }
    }

    @Test
    fun `message detail image remains clickable while preview is loading`() {
        var openCount = 0

        rule.setContent {
            WearAppTheme {
                MessageDetailedBody(
                    item = anImageTimelineItem().copy(
                        mediaPreview = WatchMediaPreview(widthPx = 256, heightPx = 256, mimeType = "image/jpeg"),
                    ),
                    mediaPreviewBytes = null,
                    onOpenImage = { openCount += 1 },
                )
            }
        }

        rule.onNode(hasClickAction()).performClick()

        rule.runOnIdle {
            assertThat(openCount).isEqualTo(1)
        }
    }

    @Test
    fun `message detail voice shows play action without preloaded audio url`() {
        var requestedItem: WatchTimelineItem? = null
        val playText = "▶  ${rule.activity.getString(R.string.play_voice_message)}"

        rule.setContent {
            WearAppTheme {
                MessageDetailedBody(
                    item = aVoiceTimelineItem(),
                    onPlayVoice = { requestedItem = it },
                )
            }
        }

        rule.onNodeWithText(playText).performClick()

        rule.runOnIdle {
            assertThat(requestedItem?.eventId).isEqualTo("\$voice:server")
        }
    }

    @Test
    fun `timeline row thread indicator opens thread root`() {
        var openedThreadRoot: String? = null
        val replyLabel = rule.activity.resources.getQuantityString(R.plurals.thread_indicator_replies, 3, 3)

        rule.setContent {
            WearAppTheme {
                TimelineMessageRow(
                    item = aTextTimelineItem().copy(
                        hasThread = true,
                        threadRootEventId = "\$thread-root:server",
                        threadReplyCount = 3,
                        threadLastReplyText = "Latest reply",
                    ),
                    onClick = {},
                    onOpenThread = { openedThreadRoot = it },
                )
            }
        }

        rule.onNodeWithText(replyLabel).performClick()
        rule.onNodeWithText("Latest reply").assertExists()

        rule.runOnIdle {
            assertThat(openedThreadRoot).isEqualTo("\$thread-root:server")
        }
    }

    @Test
    fun `timeline row exposes long press action`() {
        var longPressed = false

        rule.setContent {
            WearAppTheme {
                TimelineMessageRow(
                    item = aTextTimelineItem(),
                    onClick = {},
                    onOpenThread = null,
                    onLongPress = { longPressed = true },
                )
            }
        }

        rule.onNode(hasText("Alice") and hasLongClickAction())
            .performSemanticsAction(SemanticsActions.OnLongClick)

        rule.runOnIdle { assertThat(longPressed).isTrue() }
    }

    @Test
    fun `reaction summary opens reaction details`() {
        var opened = false
        rule.setContent {
            WearAppTheme {
                TimelineMessageRow(
                    item = aTextTimelineItem().copy(
                        reactions = listOf(WatchReactionSummary("❤️", 2, reactedBySelf = false)),
                    ),
                    onClick = {},
                    onOpenThread = null,
                    onReactionsClick = { opened = true },
                )
            }
        }

        rule.onNodeWithText("❤️ 2").performClick()
        rule.runOnIdle { assertThat(opened).isTrue() }
    }

    @Test
    fun `pending watch message shows sending state instead of internal sender`() {
        val sending = rule.activity.getString(R.string.screen_room_sending)

        rule.setContent {
            WearAppTheme {
                TimelineMessageRow(
                    item = aTextTimelineItem().copy(
                        eventId = "\$watch-local-test",
                        senderId = "@watch-local",
                        senderDisplayName = null,
                        isOwn = true,
                    ),
                    onClick = {},
                    onOpenThread = null,
                )
            }
        }

        rule.onNodeWithText(sending).assertExists()
        rule.onNodeWithText("@watch-local").assertDoesNotExist()
    }

    private fun anImageTimelineItem() = WatchTimelineItem(
        eventId = "\$image:server",
        roomId = "!room:server",
        senderId = "@alice:server",
        senderDisplayName = "Alice",
        timestampMs = 1L,
        kind = WatchTimelineItemKind.IMAGE,
        bodyText = "Vacation photo",
    )

    private fun aTextTimelineItem() = WatchTimelineItem(
        eventId = "\$text:server",
        roomId = "!room:server",
        senderId = "@alice:server",
        senderDisplayName = "Alice",
        timestampMs = 2L,
        kind = WatchTimelineItemKind.TEXT,
        bodyText = "Hello from the watch",
    )

    private fun aVoiceTimelineItem() = WatchTimelineItem(
        eventId = "\$voice:server",
        roomId = "!room:server",
        senderId = "@alice:server",
        senderDisplayName = "Alice",
        timestampMs = 3L,
        kind = WatchTimelineItemKind.VOICE,
        voiceMessageMeta = WatchVoiceMeta(
            durationMs = 3_000L,
            mimeType = "audio/ogg",
            sizeBytes = 12_000L,
            audioUrl = null,
        ),
    )

    private fun createPreviewBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.MAGENTA)
        }
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
    }

    private fun hasLongClickAction(): SemanticsMatcher = SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)
}
