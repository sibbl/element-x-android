/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui.room

import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.element.android.watchbridge.contract.WatchTimelineItem
import io.element.android.watchbridge.contract.WatchTimelineItemKind
import io.element.android.wearapp.ui.theme.WearAppTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class MessageDetailViewTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `long message detail content is scrollable to the end`() {
        lateinit var scrollState: ScrollState
        val messageBody = (1..120).joinToString(separator = "\n") { index -> "Detail line $index" }

        rule.setContent {
            scrollState = rememberScrollState()
            WearAppTheme {
                MessageDetailView(
                    state = MessageDetailViewState(
                        roomDisplayName = "Long room",
                        item = textItem(messageBody),
                    ),
                    onReply = {},
                    onVoice = null,
                    onReadAloud = {},
                    onOpenOrStartThread = {},
                    onSendReaction = {},
                    scrollState = scrollState,
                )
            }
        }

        rule.waitUntil(timeoutMillis = 5_000L) { scrollState.maxValue > 0 }

        rule.runOnIdle {
            runBlocking {
                scrollState.scrollTo(scrollState.maxValue)
            }
            assertThat(scrollState.value).isEqualTo(scrollState.maxValue)
        }
    }

    private fun textItem(body: String) = WatchTimelineItem(
        eventId = "\$event:server",
        roomId = "!room:server",
        senderId = "@alice:server",
        senderDisplayName = "Alice",
        timestampMs = 1L,
        kind = WatchTimelineItemKind.TEXT,
        bodyText = body,
    )
}
