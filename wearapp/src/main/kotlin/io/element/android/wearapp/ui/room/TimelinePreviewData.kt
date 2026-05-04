/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui.room

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.element.android.watchbridge.contract.WatchReactionSummary
import io.element.android.watchbridge.contract.WatchTimelineItem
import io.element.android.watchbridge.contract.WatchTimelineItemKind
import io.element.android.watchbridge.contract.WatchVoiceMeta
import io.element.android.wearapp.ui.common.ComposerBar
import io.element.android.wearapp.ui.theme.WearAppTheme

/**
 * Multi-device Wear preview: small round, large round, and square watch faces against a black
 * background so we can iterate on design quickly in Android Studio.
 */
@Preview(
    name = "Wear small round",
    group = "Wear devices",
    device = "id:wearos_small_round",
    showBackground = true,
    backgroundColor = 0xFF000000,
)
@Preview(
    name = "Wear large round",
    group = "Wear devices",
    device = "id:wearos_large_round",
    showBackground = true,
    backgroundColor = 0xFF000000,
)
@Preview(
    name = "Wear square",
    group = "Wear devices",
    device = "id:wearos_square",
    showBackground = true,
    backgroundColor = 0xFF000000,
)
internal annotation class WearPreviewDevices

@Composable
internal fun WearPreviewBox(content: @Composable () -> Unit) {
    WearAppTheme(content = content)
}

// ---- Demo data ---------------------------------------------------------------------------------

internal object TimelineDemoData {

    private const val ROOM_ID = "!demoroom:matrix.org"

    val aliceTextMessage = WatchTimelineItem(
        eventId = "$1",
        roomId = ROOM_ID,
        senderId = "@alice:matrix.org",
        senderDisplayName = "Alice",
        timestampMs = 1_745_000_000_000L,
        kind = WatchTimelineItemKind.TEXT,
        bodyText = "Heading out in 10. Want me to grab coffee on the way?",
        formattedText = null,
        isOwn = false,
    )

    val bobLongTextMessage = WatchTimelineItem(
        eventId = "$2",
        roomId = ROOM_ID,
        senderId = "@bob:matrix.org",
        senderDisplayName = "Bob",
        timestampMs = 1_745_000_001_000L,
        kind = WatchTimelineItemKind.TEXT,
        bodyText = "Yes please! Oat milk latte if they have it. " +
            "If not a flat white works. Thanks so much, you're a legend ❤️",
        isOwn = false,
        reactions = listOf(
            WatchReactionSummary(key = "❤️", count = 2, reactedBySelf = true),
            WatchReactionSummary(key = "👍", count = 1, reactedBySelf = false),
        ),
    )

    val ownReplyTextMessage = WatchTimelineItem(
        eventId = "$3",
        roomId = ROOM_ID,
        senderId = "@me:matrix.org",
        senderDisplayName = "Me",
        timestampMs = 1_745_000_002_000L,
        kind = WatchTimelineItemKind.TEXT,
        bodyText = "On it. ETA 12 mins.",
        isOwn = true,
    )

    val emoteMessage = WatchTimelineItem(
        eventId = "$4",
        roomId = ROOM_ID,
        senderId = "@carol:matrix.org",
        senderDisplayName = "Carol",
        timestampMs = 1_745_000_003_000L,
        kind = WatchTimelineItemKind.EMOTE,
        bodyText = "waves enthusiastically",
        isOwn = false,
    )

    val noticeMessage = WatchTimelineItem(
        eventId = "$5",
        roomId = ROOM_ID,
        senderId = "@bot:matrix.org",
        senderDisplayName = "CI Bot",
        timestampMs = 1_745_000_004_000L,
        kind = WatchTimelineItemKind.NOTICE,
        bodyText = "Build #4271 succeeded on main",
        isOwn = false,
    )

    val imageMessage = WatchTimelineItem(
        eventId = "$6",
        roomId = ROOM_ID,
        senderId = "@dev:matrix.org",
        senderDisplayName = "Devon",
        timestampMs = 1_745_000_005_000L,
        kind = WatchTimelineItemKind.IMAGE,
        bodyText = "sunset.png",
        isOwn = false,
    )

    val videoMessage = WatchTimelineItem(
        eventId = "$7",
        roomId = ROOM_ID,
        senderId = "@dev:matrix.org",
        senderDisplayName = "Devon",
        timestampMs = 1_745_000_006_000L,
        kind = WatchTimelineItemKind.VIDEO,
        bodyText = "trail-cam-clip.mp4",
        isOwn = false,
    )

    val fileMessage = WatchTimelineItem(
        eventId = "$8",
        roomId = ROOM_ID,
        senderId = "@dev:matrix.org",
        senderDisplayName = "Devon",
        timestampMs = 1_745_000_007_000L,
        kind = WatchTimelineItemKind.FILE,
        bodyText = "Q2-roadmap.pdf",
        isOwn = false,
    )

    val voiceMessage = WatchTimelineItem(
        eventId = "$9",
        roomId = ROOM_ID,
        senderId = "@erin:matrix.org",
        senderDisplayName = "Erin",
        timestampMs = 1_745_000_008_000L,
        kind = WatchTimelineItemKind.VOICE,
        bodyText = null,
        isOwn = false,
        voiceMessageMeta = WatchVoiceMeta(
            durationMs = 28_000L,
            waveform = listOf(
                10, 24, 38, 60, 78, 90, 80, 72, 55, 40,
                25, 18, 32, 48, 70, 82, 90, 78, 60, 45,
                30, 22, 18, 28, 50, 70, 88, 82, 65, 40,
                25, 12, 8, 18, 36, 55, 72, 60, 38, 18,
            ),
            mimeType = "audio/ogg",
            sizeBytes = 184_320,
        ),
    )

    val locationLikeMessage = WatchTimelineItem(
        eventId = "$10",
        roomId = ROOM_ID,
        senderId = "@frank:matrix.org",
        senderDisplayName = "Frank",
        timestampMs = 1_745_000_009_000L,
        kind = WatchTimelineItemKind.UNSUPPORTED,
        bodyText = "📍 Sent a location · 51.5074° N, 0.1278° W",
        isOwn = false,
    )

    val redactedMessage = WatchTimelineItem(
        eventId = "$11",
        roomId = ROOM_ID,
        senderId = "@gina:matrix.org",
        senderDisplayName = "Gina",
        timestampMs = 1_745_000_010_000L,
        kind = WatchTimelineItemKind.REDACTED,
        bodyText = null,
        isOwn = false,
    )

    val stateMessage = WatchTimelineItem(
        eventId = "$12",
        roomId = ROOM_ID,
        senderId = "@server:matrix.org",
        senderDisplayName = null,
        timestampMs = 1_745_000_011_000L,
        kind = WatchTimelineItemKind.STATE,
        bodyText = "Alice changed the room name to \"Coffee crew\"",
        isOwn = false,
    )

    val threadedTextMessage = WatchTimelineItem(
        eventId = "$13",
        roomId = ROOM_ID,
        senderId = "@harper:matrix.org",
        senderDisplayName = "Harper",
        timestampMs = 1_745_000_012_000L,
        kind = WatchTimelineItemKind.TEXT,
        bodyText = "Started a side discussion on the new icon set.",
        isOwn = false,
        hasThread = true,
        threadRootEventId = "$13",
        threadReplyCount = 5,
        threadLastReplyText = "Iris: I prefer the rounded outlines, way more friendly",
    )

    val threadedVoiceMessage = WatchTimelineItem(
        eventId = "$14",
        roomId = ROOM_ID,
        senderId = "@iris:matrix.org",
        senderDisplayName = "Iris",
        timestampMs = 1_745_000_013_000L,
        kind = WatchTimelineItemKind.VOICE,
        bodyText = null,
        isOwn = false,
        hasThread = true,
        threadRootEventId = "$14",
        threadReplyCount = 2,
        threadLastReplyText = "Jordan: agreed, the punchier version please",
        voiceMessageMeta = WatchVoiceMeta(
            durationMs = 12_500L,
            waveform = listOf(20, 60, 90, 70, 35, 50, 80, 95, 70, 45, 30, 60, 85, 55, 25),
            mimeType = "audio/ogg",
            sizeBytes = 92_160,
        ),
    )

    val richReactionsMessage = WatchTimelineItem(
        eventId = "$15",
        roomId = ROOM_ID,
        senderId = "@kai:matrix.org",
        senderDisplayName = "Kai",
        timestampMs = 1_745_000_014_000L,
        kind = WatchTimelineItemKind.TEXT,
        bodyText = "Shipping the watch UI update tonight!",
        isOwn = false,
        reactions = listOf(
            WatchReactionSummary("🎉", 4, reactedBySelf = true),
            WatchReactionSummary("🚀", 3, reactedBySelf = false),
            WatchReactionSummary("👏", 2, reactedBySelf = false),
            WatchReactionSummary("❤️", 5, reactedBySelf = true),
            WatchReactionSummary("🤖", 1, reactedBySelf = false),
            WatchReactionSummary("✨", 1, reactedBySelf = false),
        ),
    )

    val allKinds: List<WatchTimelineItem> = listOf(
        aliceTextMessage,
        bobLongTextMessage,
        ownReplyTextMessage,
        emoteMessage,
        noticeMessage,
        imageMessage,
        videoMessage,
        fileMessage,
        voiceMessage,
        locationLikeMessage,
        redactedMessage,
        stateMessage,
        threadedTextMessage,
        threadedVoiceMessage,
        richReactionsMessage,
    )
}

internal class TimelineItemPreviewProvider : PreviewParameterProvider<WatchTimelineItem> {
    override val values: Sequence<WatchTimelineItem> = TimelineDemoData.allKinds.asSequence()
}

// ---- Component previews ------------------------------------------------------------------------

@WearPreviewDevices
@Composable
private fun TimelineMessageRowPreview(
    @PreviewParameter(TimelineItemPreviewProvider::class) item: WatchTimelineItem,
) {
    WearPreviewBox {
        TimelineMessageRow(
            item = item,
            onClick = {},
            onOpenThread = {},
        )
    }
}

@WearPreviewDevices
@Composable
private fun ComposerBarPreview() {
    WearPreviewBox {
        ComposerBar(
            onReply = {},
            onVoice = {},
            contextLabel = "Reply to Alice",
        )
    }
}

@WearPreviewDevices
@Composable
private fun ComposerBarNoVoicePreview() {
    WearPreviewBox {
        ComposerBar(
            onReply = {},
            onVoice = null,
            contextLabel = null,
        )
    }
}

@WearPreviewDevices
@Composable
private fun ThreadIndicatorChipPreview() {
    WearPreviewBox {
        ThreadIndicatorChip(
            replyCount = 5,
            lastReplyPreview = "Iris: I prefer the rounded outlines, way more friendly",
            onClick = {},
        )
    }
}

// ---- Screen-level previews ---------------------------------------------------------------------

@WearPreviewDevices
@Composable
private fun RoomViewPopulatedPreview() {
    WearPreviewBox {
        RoomView(
            state = RoomViewState(
                timelineKey = "preview-populated",
                displayName = "Coffee crew",
                items = TimelineDemoData.allKinds,
            ),
            onMessageSelected = {},
            onOpenThread = {},
            onReply = {},
            onVoice = {},
        )
    }
}

@WearPreviewDevices
@Composable
private fun RoomViewEmptyPreview() {
    WearPreviewBox {
        RoomView(
            state = RoomViewState(
                timelineKey = "preview-empty",
                displayName = "Alice",
                items = emptyList(),
            ),
            onMessageSelected = {},
            onOpenThread = {},
            onReply = {},
            onVoice = {},
        )
    }
}

@WearPreviewDevices
@Composable
private fun RoomViewThreadPreview() {
    WearPreviewBox {
        RoomView(
            state = RoomViewState(
                timelineKey = "preview-thread",
                displayName = "Thread · Coffee crew",
                items = listOf(
                    TimelineDemoData.threadedTextMessage.copy(hasThread = false),
                    TimelineDemoData.aliceTextMessage,
                    TimelineDemoData.ownReplyTextMessage,
                    TimelineDemoData.voiceMessage,
                ),
                composerContextLabel = "Reply in thread",
            ),
            onMessageSelected = {},
            onOpenThread = null,
            onReply = {},
            onVoice = {},
        )
    }
}

internal class MessageDetailPreviewProvider : PreviewParameterProvider<WatchTimelineItem> {
    override val values: Sequence<WatchTimelineItem> = sequenceOf(
        TimelineDemoData.bobLongTextMessage,
        TimelineDemoData.voiceMessage,
        TimelineDemoData.imageMessage,
        TimelineDemoData.videoMessage,
        TimelineDemoData.fileMessage,
        TimelineDemoData.locationLikeMessage,
        TimelineDemoData.redactedMessage,
        TimelineDemoData.threadedTextMessage,
        TimelineDemoData.richReactionsMessage,
    )
}

@WearPreviewDevices
@Composable
private fun MessageDetailViewPreview(
    @PreviewParameter(MessageDetailPreviewProvider::class) item: WatchTimelineItem,
) {
    WearPreviewBox {
        MessageDetailView(
            state = MessageDetailViewState(
                roomDisplayName = "Coffee crew",
                item = item,
            ),
            onReply = {},
            onVoice = {},
            onReadAloud = {},
            onOpenOrStartThread = {},
            onSendReaction = {},
        )
    }
}

@WearPreviewDevices
@Composable
private fun MessageDetailViewLoadingPreview() {
    WearPreviewBox {
        MessageDetailView(
            state = MessageDetailViewState(
                roomDisplayName = "Coffee crew",
                item = null,
            ),
            onReply = {},
            onVoice = {},
            onReadAloud = {},
            onOpenOrStartThread = {},
            onSendReaction = {},
        )
    }
}
