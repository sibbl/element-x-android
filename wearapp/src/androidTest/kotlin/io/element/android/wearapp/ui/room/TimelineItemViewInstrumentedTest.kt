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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.compose.material.MaterialTheme
import com.google.common.truth.Truth.assertThat
import io.element.android.watchbridge.contract.WatchTimelineItem
import io.element.android.watchbridge.contract.WatchTimelineItemKind
import io.element.android.wearapp.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class TimelineItemViewInstrumentedTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun timeline_image_row_shows_unavailable_placeholder_when_preview_is_missing() {
        val unavailableText = rule.activity.getString(R.string.screen_media_preview_unavailable)

        rule.setContent {
            MaterialTheme {
                TimelineMessageRow(
                    item = anImageTimelineItem(),
                    mediaPreviewBytes = null,
                    onClick = {},
                    onOpenThread = null,
                    showSender = false,
                )
            }
        }

        rule.onNodeWithText(unavailableText).assertExists()
        rule.onNodeWithText("Vacation photo").assertExists()
    }

    @Test
    fun timeline_image_row_invokes_click_handler_on_emulator() {
        var clickCount = 0

        rule.setContent {
            MaterialTheme {
                TimelineMessageRow(
                    item = anImageTimelineItem(),
                    mediaPreviewBytes = createPreviewBytes(),
                    onClick = { clickCount += 1 },
                    onOpenThread = null,
                    showSender = false,
                )
            }
        }

        rule.onNodeWithText("Vacation photo", useUnmergedTree = true).performClick()
        rule.runOnIdle {
            assertThat(clickCount).isEqualTo(1)
        }
    }

    @Test
    fun timeline_image_row_hides_unavailable_placeholder_when_preview_is_available_on_emulator() {
        val unavailableText = rule.activity.getString(R.string.screen_media_preview_unavailable)

        rule.setContent {
            MaterialTheme {
                TimelineMessageRow(
                    item = anImageTimelineItem(),
                    mediaPreviewBytes = createPreviewBytes(),
                    onClick = {},
                    onOpenThread = null,
                    showSender = false,
                )
            }
        }

        rule.waitUntil(timeoutMillis = 5_000L) {
            rule.onAllNodesWithText(unavailableText, useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithText("Vacation photo").assertExists()
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

    private fun createPreviewBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.GREEN)
        }
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
    }
}
