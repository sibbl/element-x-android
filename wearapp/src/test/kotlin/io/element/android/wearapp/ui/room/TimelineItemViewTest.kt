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
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.compose.material.MaterialTheme
import com.google.common.truth.Truth.assertThat
import io.element.android.watchbridge.contract.WatchTimelineItem
import io.element.android.watchbridge.contract.WatchTimelineItemKind
import io.element.android.wearapp.R
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
    fun `timeline image row shows unavailable placeholder when preview bytes are missing`() {
        val unavailableText = rule.activity.getString(R.string.screen_media_preview_unavailable)

        rule.setContent {
            MaterialTheme {
                TimelineMessageRow(
                    item = anImageTimelineItem(),
                    mediaPreviewBytes = null,
                    onClick = {},
                    onOpenThread = null,
                )
            }
        }

        rule.onNodeWithText(unavailableText).assertExists()
        rule.onNodeWithText("Vacation photo").assertExists()
    }

    @Test
    fun `timeline image row hides unavailable placeholder when preview bytes are present`() {
        val unavailableText = rule.activity.getString(R.string.screen_media_preview_unavailable)
        val previewBytes = createPreviewBytes()

        rule.setContent {
            MaterialTheme {
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
            eraseColor(Color.MAGENTA)
        }
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
    }
}
