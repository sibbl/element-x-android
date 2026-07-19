/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui.room

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
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

    @Test
    fun `plain body text preserves normalized line breaks when formatted text is absent`() {
        val item = aTextTimelineItem(
            bodyText = "First line\r\nSecond line\rThird line",
            formattedText = null,
        )

        assertThat(item.displayText()).isEqualTo("First line\nSecond line\nThird line")
        assertThat(item.richDisplayText().text).isEqualTo("First line\nSecond line\nThird line")
    }

    @Test
    fun `display text preserves block line breaks from formatted html`() {
        val item = aTextTimelineItem(
            formattedText = "<p>Intro</p><ul><li>First bullet</li><li>Second bullet</li></ul><blockquote>Quote<br>continued</blockquote>",
        )

        assertThat(item.displayText()).isEqualTo("Intro\nFirst bullet\nSecond bullet\nQuote\ncontinued")
    }

    @Test
    fun `rich display text preserves formatting spans`() {
        val item = aTextTimelineItem(
            formattedText = "<strong>Bold</strong> <em>italic</em><br><a href=\"https://example.org\">link</a>",
        )

        val richText = item.richDisplayText()

        assertThat(richText.text).isEqualTo("Bold italic\nlink")
        assertThat(richText.spanStyles.any { it.item.fontWeight == FontWeight.Bold && richText.text.substring(it.start, it.end) == "Bold" })
            .isTrue()
        assertThat(richText.spanStyles.any { it.item.fontStyle == FontStyle.Italic && richText.text.substring(it.start, it.end) == "italic" })
            .isTrue()
        assertThat(richText.getStringAnnotations("URL", 0, richText.length).single().item)
            .isEqualTo("https://example.org")
    }

    @Test
    fun `tts readability requires real readable text`() {
        val textItem = aTextTimelineItem(bodyText = "Hello", formattedText = null)
        val voiceItem = WatchTimelineItem(
            eventId = "\$voice:server",
            roomId = "!room:server",
            senderId = "@alice:server",
            senderDisplayName = "Alice",
            timestampMs = 1L,
            kind = WatchTimelineItemKind.VOICE,
            bodyText = null,
            readableByTts = false,
        )

        assertThat(textItem.hasTextForTts()).isTrue()
        assertThat(voiceItem.hasTextForTts()).isFalse()
    }

    private fun aTextTimelineItem(
        bodyText: String = "Plain fallback",
        formattedText: String?,
    ) = WatchTimelineItem(
        eventId = "\$event:server",
        roomId = "!room:server",
        senderId = "@alice:server",
        senderDisplayName = "Alice",
        timestampMs = 1L,
        kind = WatchTimelineItemKind.TEXT,
        bodyText = bodyText,
        formattedText = formattedText,
    )
}
