/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui.room

import com.google.common.truth.Truth.assertThat
import io.element.android.watchbridge.contract.WatchTimelineItem
import io.element.android.watchbridge.contract.WatchTimelineItemKind
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoomTimelineStateTest {
    @Test
    fun `display text preserves formatted line breaks`() {
        val item = aTextTimelineItem(
            formattedText = "<p>First line</p><p>Second<br>third</p>",
        )

        assertThat(item.displayText()).isEqualTo("First line\nSecond\nthird")
    }

    @Test
    fun `display text collapses inline whitespace without removing line breaks`() {
        val item = aTextTimelineItem(
            formattedText = "First    line<br>Second\tline",
        )

        assertThat(item.displayText()).isEqualTo("First line\nSecond line")
    }

    private fun aTextTimelineItem(
        formattedText: String,
    ) = WatchTimelineItem(
        eventId = "\$event:server",
        roomId = "!room:server",
        senderId = "@alice:server",
        senderDisplayName = "Alice",
        timestampMs = 1L,
        kind = WatchTimelineItemKind.TEXT,
        bodyText = "Plain fallback",
        formattedText = formattedText,
    )
}
