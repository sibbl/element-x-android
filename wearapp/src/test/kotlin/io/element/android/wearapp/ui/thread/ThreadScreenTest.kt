/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui.thread

import com.google.common.truth.Truth.assertThat
import io.element.android.watchbridge.contract.WatchThreadItem
import io.element.android.watchbridge.contract.WatchTimelineItemKind
import io.element.android.watchbridge.contract.WatchVoiceMeta
import io.element.android.wearapp.ui.room.hasTextForTts
import org.junit.Test

class ThreadScreenTest {
    @Test
    fun `thread voice item conversion keeps read aloud disabled`() {
        val threadItem = WatchThreadItem(
            eventId = "\$voice:server",
            threadRootEventId = "\$root:server",
            roomId = "!room:server",
            senderId = "@alice:server",
            senderDisplayName = "Alice",
            timestampMs = 1L,
            kind = WatchTimelineItemKind.VOICE,
            bodyText = null,
            voiceMessageMeta = WatchVoiceMeta(
                durationMs = 3_000L,
                waveform = emptyList(),
                mimeType = "audio/ogg",
                sizeBytes = 1_024L,
            ),
        )

        val timelineItem = threadItem.toTimelineItem()

        assertThat(timelineItem.readableByTts).isFalse()
        assertThat(timelineItem.hasTextForTts()).isFalse()
    }
}
