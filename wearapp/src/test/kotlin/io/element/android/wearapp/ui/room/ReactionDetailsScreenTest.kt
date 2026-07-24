/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.wearapp.ui.room

import com.google.common.truth.Truth.assertThat
import io.element.android.watchbridge.contract.WatchReactionSender
import io.element.android.watchbridge.contract.WatchReactionSummary
import org.junit.Test

class ReactionDetailsScreenTest {
    @Test
    fun `reaction rows retain all projected senders`() {
        val reaction = WatchReactionSummary(
            key = "👍",
            count = 2,
            reactedBySelf = true,
            senders = listOf(
                WatchReactionSender("@alice:example.org", "Alice"),
                WatchReactionSender("@bob:example.org", "Bob"),
            ),
        )

        assertThat(reactionRows(reaction).map { it.sender?.displayName })
            .containsExactly("Alice", "Bob").inOrder()
    }

    @Test
    fun `reaction count remains visible when sender details are unavailable`() {
        val rows = reactionRows(WatchReactionSummary("🔥", 3, reactedBySelf = false))

        assertThat(rows).hasSize(3)
        assertThat(rows.map { it.emoji }).containsExactly("🔥", "🔥", "🔥")
        assertThat(rows.all { it.sender == null }).isTrue()
    }

    @Test
    fun `additional reactions exclude emoji already present`() {
        val reactions = listOf(
            WatchReactionSummary("😂", 1, reactedBySelf = false),
            WatchReactionSummary("👍", 2, reactedBySelf = true),
        )

        assertThat(availableQuickReactions(reactions))
            .containsExactly("❤️", "🎉", "🙏", "👀").inOrder()
    }

    @Test
    fun `optimistic add appears immediately and changes plus to minus`() {
        val reactions = applyOptimisticReactionStates(emptyList(), mapOf("👍" to true))

        assertThat(reactions).containsExactly(
            WatchReactionSummary("👍", 1, reactedBySelf = true),
        )
    }

    @Test
    fun `optimistic remove updates count and self state`() {
        val reactions = applyOptimisticReactionStates(
            listOf(WatchReactionSummary("👍", 2, reactedBySelf = true)),
            mapOf("👍" to false),
        )

        assertThat(reactions).containsExactly(
            WatchReactionSummary("👍", 1, reactedBySelf = false),
        )
    }

}
