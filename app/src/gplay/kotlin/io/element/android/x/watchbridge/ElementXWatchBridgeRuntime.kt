/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.watchbridge

import android.content.Context
import io.element.android.libraries.di.DependencyInjectionGraphOwner
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.room.CreateTimelineParams
import io.element.android.libraries.matrix.api.room.CurrentUserMembership
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.room.RoomInfo
import io.element.android.libraries.matrix.api.room.isDm
import io.element.android.libraries.matrix.api.roomlist.LatestEventValue
import io.element.android.libraries.matrix.api.roomlist.RoomList
import io.element.android.libraries.matrix.api.roomlist.RoomSummary
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.ReceiptType
import io.element.android.libraries.matrix.api.timeline.item.EventThreadInfo
import io.element.android.libraries.matrix.api.timeline.item.event.AudioMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.EmoteMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.EventContent
import io.element.android.libraries.matrix.api.timeline.item.event.EventTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.FileMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.ImageMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.MessageTypeWithAttachment
import io.element.android.libraries.matrix.api.timeline.item.event.NoticeMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.OtherMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.PollContent
import io.element.android.libraries.matrix.api.timeline.item.event.ProfileDetails
import io.element.android.libraries.matrix.api.timeline.item.event.RedactedContent
import io.element.android.libraries.matrix.api.timeline.item.event.StickerContent
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.UnableToDecryptContent
import io.element.android.libraries.matrix.api.timeline.item.event.VideoMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.VoiceMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.toEventOrTransactionId
import io.element.android.services.appnavstate.api.currentSessionId
import io.element.android.watchbridge.ElementXWatchPort
import io.element.android.watchbridge.WatchBridgeDispatcher
import io.element.android.watchbridge.WatchCompanionSettingsStore
import io.element.android.watchbridge.contract.WatchFavoriteRoom
import io.element.android.watchbridge.contract.WatchPlaybackDescriptor
import io.element.android.watchbridge.contract.WatchReactionSummary
import io.element.android.watchbridge.contract.WatchRoomKind
import io.element.android.watchbridge.contract.WatchRoomSummary
import io.element.android.watchbridge.contract.WatchThreadItem
import io.element.android.watchbridge.contract.WatchTimelineItem
import io.element.android.watchbridge.contract.WatchTimelineItemKind
import io.element.android.watchbridge.contract.WatchVoiceDraft
import io.element.android.watchbridge.contract.WatchVoiceMeta
import io.element.android.watchbridge.transport.PlayServicesWatchTransport
import io.element.android.x.di.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds

private const val ROOM_LIST_PAGE_SIZE = 30
private const val MAX_ROOM_LIST_COUNT = 200
private const val MAX_LOAD_MORE_ATTEMPTS = 8

/** Process-wide runtime that keeps the Wear bridge attached to the current Matrix session. */
object ElementXWatchBridgeRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private var activeBridge: ActiveBridge? = null
    private var _settingsStore: WatchCompanionSettingsStore? = null

    /** Lazily initialized settings store. Safe to access from any thread. */
    fun settingsStore(context: Context): WatchCompanionSettingsStore {
        _settingsStore?.let { return it }
        return synchronized(this) {
            _settingsStore ?: WatchCompanionSettingsStore(context.applicationContext).also { _settingsStore = it }
        }
    }

    fun start(context: Context) {
        val appContext = context.applicationContext
        scope.launch { getOrCreateDispatcher(appContext) }
    }

    fun dispatch(context: Context, envelope: io.element.android.watchbridge.contract.WatchSyncEnvelope) {
        val appContext = context.applicationContext
        scope.launch {
            val dispatcher = getOrCreateDispatcher(appContext)
            if (dispatcher == null) {
                Timber.w("WatchBridge command ignored because no logged-in Matrix session is available")
            } else {
                dispatcher.onEnvelope(envelope)
            }
        }
    }

    private suspend fun getOrCreateDispatcher(context: Context): WatchBridgeDispatcher? = mutex.withLock {
        Timber.d("WatchBridge initializing dispatcher")
        val graph = ((context.applicationContext as? DependencyInjectionGraphOwner)?.graph as? AppGraph)
        if (graph == null) {
            Timber.e("WatchBridge graph is null")
            return@withLock null
        }
        val sessionId = graph.activeSessionId()
        if (sessionId == null) {
            Timber.e("WatchBridge sessionId is null")
            return@withLock null
        }
        activeBridge?.takeIf { it.sessionId == sessionId }?.let { return@withLock it.dispatcher }

        activeBridge?.dispatcher?.stop()
        val clientResult = graph.matrixClientProvider.getOrRestore(sessionId)
        if (clientResult.isFailure) {
            Timber.e(clientResult.exceptionOrNull(), "WatchBridge matrix client restoration failed")
            return@withLock null
        }
        val client = clientResult.getOrNull()
        if (client == null) {
            Timber.e("WatchBridge matrix client is null after restore")
            return@withLock null
        }
        val dispatcher = WatchBridgeDispatcher(
            port = MatrixRoomListWatchPort(client),
            transport = PlayServicesWatchTransport(context),
            scope = client.sessionCoroutineScope,
            settingsStore = settingsStore(context),
        )
        dispatcher.start()
        activeBridge = ActiveBridge(sessionId, dispatcher)
        Timber.d("WatchBridge dispatcher started for session=%s", sessionId.value)
        dispatcher
    }

    private suspend fun AppGraph.activeSessionId(): SessionId? {
        appNavigationStateService.appNavigationState.value.navigationState.currentSessionId()?.let { return it }
        return sessionStore.getLatestSession()
            ?.takeIf { it.isTokenValid }
            ?.userId
            ?.let(::SessionId)
    }

    private data class ActiveBridge(
        val sessionId: SessionId,
        val dispatcher: WatchBridgeDispatcher,
    )
}

private class MatrixRoomListWatchPort(
    private val client: MatrixClient,
) : ElementXWatchPort {
    private val roomList = client.roomListService.createRoomList(
        pageSize = ROOM_LIST_PAGE_SIZE,
        source = RoomList.Source.All,
        coroutineScope = client.sessionCoroutineScope,
    )
    private val joinedRooms = ConcurrentHashMap<String, JoinedRoom>()

    override fun favorites(): Flow<List<WatchFavoriteRoom>> = roomList.summaries
        .onEach { summaries -> subscribeToVisibleRooms(summaries, ROOM_LIST_PAGE_SIZE) }
        .map { summaries -> summaries.toWatchRooms() }
        .distinctUntilChanged()

    override suspend fun ensureRoomListLoaded(minimumCount: Int) {
        val target = minimumCount.coerceIn(ROOM_LIST_PAGE_SIZE, MAX_ROOM_LIST_COUNT)
        var attempts = 0
        while (currentRoomCount() < target && attempts < MAX_LOAD_MORE_ATTEMPTS && !hasLoadedEverything()) {
            val previousCount = currentRoomCount()
            roomList.loadMore()
            withTimeoutOrNull(500.milliseconds) {
                roomList.summaries.first { summaries -> summaries.size > previousCount || hasLoadedEverything() }
            }
            attempts++
        }
        subscribeToVisibleRooms(currentSummaries(), target)
    }

    override suspend fun roomSummary(roomId: String): WatchRoomSummary? {
        val room = joinedRoom(roomId) ?: return null
        val info = room.info()
        return WatchRoomSummary(
            roomId = roomId,
            displayName = info.name ?: info.canonicalAlias?.value ?: roomId,
            avatarUri = info.avatarUrl?.mxcToHttpThumbnail(64),
            kind = info.watchKind(),
            isEncrypted = info.isEncrypted == true,
            canSendMessages = true,
            timelineVersion = room.syncUpdateFlow.value,
            lastSyncTsMs = System.currentTimeMillis(),
        )
    }

    override fun roomTimeline(roomId: String, limit: Int): Flow<List<WatchTimelineItem>> = flow {
        val room = joinedRoom(roomId) ?: return@flow
        room.subscribeToSync()
        // Kick off backward pagination to ensure items are loaded (especially for encrypted rooms
        // where the live timeline may initially be empty until decryption completes).
        val timeline = room.liveTimeline
        runCatching { timeline.paginate(io.element.android.libraries.matrix.api.timeline.Timeline.PaginationDirection.BACKWARDS) }
            .onFailure { Timber.d(it, "WatchBridge initial back-paginate for room=%s", roomId) }
        emitAll(
            room.liveTimeline.timelineItems.map { items ->
                items.toWatchTimelineItems(roomId)
                    .sortedBy { it.timestampMs }
                    .takeLast(limit)
            }
        )
    }

    override fun threadTimeline(roomId: String, threadRootEventId: String, limit: Int): Flow<List<WatchThreadItem>> = flow {
        val room = joinedRoom(roomId) ?: return@flow
        val timeline = room.createTimeline(CreateTimelineParams.Threaded(ThreadId(threadRootEventId))).getOrNull() ?: return@flow
        try {
            emitAll(
                timeline.timelineItems.map { items ->
                    items.toWatchThreadItems(roomId, threadRootEventId)
                        .sortedBy { it.timestampMs }
                        .takeLast(limit)
                }
            )
        } finally {
            timeline.close()
        }
    }

    override suspend fun sendText(roomId: String, threadRootEventId: String?, text: String): Result<String> {
        val room = joinedRoom(roomId) ?: return Result.failure(NoSuchElementException("room not found"))
        return if (threadRootEventId == null) {
            room.liveTimeline.sendMessage(text, htmlBody = null, intentionalMentions = emptyList()).map { "" }
        } else {
            val timeline = room.createTimeline(CreateTimelineParams.Threaded(ThreadId(threadRootEventId))).getOrThrow()
            try {
                timeline.sendMessage(text, htmlBody = null, intentionalMentions = emptyList()).map { "" }
            } finally {
                timeline.close()
            }
        }
    }

    override suspend fun sendReaction(roomId: String, eventId: String, reactionKey: String): Result<Unit> {
        val room = joinedRoom(roomId) ?: return Result.failure(NoSuchElementException("room not found"))
        return room.liveTimeline.toggleReaction(reactionKey, EventId(eventId).toEventOrTransactionId()).map { Unit }
    }

    override suspend fun sendVoiceMessage(draft: WatchVoiceDraft, audioBytes: ByteArray): Result<String> {
        return Result.failure(UnsupportedOperationException("watch voice upload is not wired yet"))
    }

    override suspend fun playbackDescriptor(roomId: String, eventId: String): Result<WatchPlaybackDescriptor> {
        return Result.failure(UnsupportedOperationException("watch playback is not wired yet"))
    }

    override suspend fun markAsRead(roomId: String, eventId: String): Result<Unit> {
        val room = joinedRoom(roomId) ?: return Result.failure(NoSuchElementException("room not found"))
        return room.liveTimeline.sendReadReceipt(EventId(eventId), ReceiptType.READ_PRIVATE)
    }

    private suspend fun joinedRoom(roomId: String): JoinedRoom? {
        joinedRooms[roomId]?.let { return it }
        return client.getJoinedRoom(RoomId(roomId))?.also { room ->
            joinedRooms[roomId] = room
            room.subscribeToSync()
        }
    }

    private fun currentSummaries(): List<RoomSummary> = roomList.summaries.replayCache.firstOrNull().orEmpty()

    private fun currentRoomCount(): Int = currentSummaries().size

    private fun hasLoadedEverything(): Boolean {
        val loaded = roomList.loadingState.value as? RoomList.LoadingState.Loaded ?: return false
        return loaded.numberOfRooms <= currentRoomCount()
    }

    private suspend fun subscribeToVisibleRooms(summaries: List<RoomSummary>, count: Int) {
        val roomIds = summaries.asSequence()
            .filter { it.isWatchVisibleRoom() }
            .take(count)
            .map { it.roomId }
            .toList()
        runCatching { client.roomListService.subscribeToVisibleRooms(roomIds) }
            .onFailure { Timber.w(it, "WatchBridge visible room subscription failed") }
    }
}

private fun List<RoomSummary>.toWatchRooms(): List<WatchFavoriteRoom> {
    val rooms = filter { it.isWatchVisibleRoom() }
    return rooms.filter { it.info.isFavorite }.map { it.toWatchRoom() } +
        rooms.filterNot { it.info.isFavorite }.map { it.toWatchRoom() }
}

private fun RoomSummary.isWatchVisibleRoom(): Boolean {
    return info.currentUserMembership == CurrentUserMembership.JOINED && !info.isSpace
}

private fun RoomSummary.toWatchRoom(): WatchFavoriteRoom {
    val unreadCount = max(info.numUnreadMessages, info.numUnreadNotifications).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return WatchFavoriteRoom(
        roomId = roomId.value,
        displayName = info.name ?: info.canonicalAlias?.value ?: roomId.value,
        avatarUri = info.avatarUrl?.mxcToHttpThumbnail(64),
        kind = info.watchKind(),
        unreadCount = unreadCount,
        hasMentions = info.numUnreadMentions > 0,
        lastActivityTsMs = latestEventTimestamp ?: 0L,
        lastPreviewText = latestEvent.previewText(),
        isFavorite = info.isFavorite,
    )
}

private fun RoomInfo.watchKind(): WatchRoomKind = if (isDm) WatchRoomKind.DM else WatchRoomKind.GROUP

private fun LatestEventValue.previewText(): String? = when (this) {
    LatestEventValue.None -> null
    is LatestEventValue.Local -> content.previewText()?.let { if (isSending) "Sending: $it" else it }
    is LatestEventValue.Remote -> content.previewText()
    is LatestEventValue.RoomInvite -> "Invite"
}

private fun List<MatrixTimelineItem>.toWatchTimelineItems(roomId: String): List<WatchTimelineItem> = mapNotNull { item ->
    val event = (item as? MatrixTimelineItem.Event)?.event ?: return@mapNotNull null
    event.toWatchTimelineItem(roomId)
}

private fun List<MatrixTimelineItem>.toWatchThreadItems(roomId: String, threadRootEventId: String): List<WatchThreadItem> = mapNotNull { item ->
    val event = (item as? MatrixTimelineItem.Event)?.event ?: return@mapNotNull null
    val eventId = event.eventId?.value ?: return@mapNotNull null
    WatchThreadItem(
        eventId = eventId,
        threadRootEventId = threadRootEventId,
        roomId = roomId,
        senderId = event.sender.value,
        senderDisplayName = event.senderProfile.displayName(),
        timestampMs = event.timestamp,
        kind = event.content.watchKind(),
        bodyText = event.content.previewText(),
        isOwn = event.isOwn,
        reactions = event.watchReactions(),
        voiceMessageMeta = event.content.voiceMeta(),
    )
}

private fun EventTimelineItem.toWatchTimelineItem(roomId: String): WatchTimelineItem? {
    val eventId = eventId?.value ?: return null
    val threadInfo = threadInfo()
    val threadRootEventId = when (threadInfo) {
        is EventThreadInfo.ThreadRoot -> this.eventId?.value
        is EventThreadInfo.ThreadResponse -> threadInfo.threadRootId.value
        null -> null
    }
    return WatchTimelineItem(
        eventId = eventId,
        roomId = roomId,
        senderId = sender.value,
        senderDisplayName = senderProfile.displayName(),
        timestampMs = timestamp,
        kind = content.watchKind(),
        bodyText = content.previewText()?.take(300),
        formattedText = (content as? MessageContent)?.type?.formattedBody()?.take(500),
        isOwn = isOwn,
        isEdited = (content as? MessageContent)?.isEdited == true,
        hasThread = threadInfo is EventThreadInfo.ThreadRoot,
        threadRootEventId = threadRootEventId,
        threadReplyCount = (threadInfo as? EventThreadInfo.ThreadRoot)?.summary?.numberOfReplies?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0,
        reactions = watchReactions(),
        voiceMessageMeta = content.voiceMeta(),
        readableByTts = content.watchKind() in setOf(WatchTimelineItemKind.TEXT, WatchTimelineItemKind.EMOTE, WatchTimelineItemKind.NOTICE),
    )
}

private fun EventTimelineItem.watchReactions(): List<WatchReactionSummary> = reactions.map { reaction ->
    WatchReactionSummary(
        key = reaction.key,
        count = reaction.senders.size,
        reactedBySelf = false,
    )
}

private fun EventContent.watchKind(): WatchTimelineItemKind = when (this) {
    is MessageContent -> when (type) {
        is TextMessageType -> WatchTimelineItemKind.TEXT
        is EmoteMessageType -> WatchTimelineItemKind.EMOTE
        is NoticeMessageType -> WatchTimelineItemKind.NOTICE
        is ImageMessageType -> WatchTimelineItemKind.IMAGE
        is VideoMessageType -> WatchTimelineItemKind.VIDEO
        is FileMessageType -> WatchTimelineItemKind.FILE
        is AudioMessageType,
        is VoiceMessageType -> WatchTimelineItemKind.VOICE
        else -> WatchTimelineItemKind.UNSUPPORTED
    }
    is RedactedContent -> WatchTimelineItemKind.REDACTED
    is StickerContent -> WatchTimelineItemKind.IMAGE
    is PollContent -> WatchTimelineItemKind.UNSUPPORTED
    is UnableToDecryptContent -> WatchTimelineItemKind.UNSUPPORTED
    else -> WatchTimelineItemKind.STATE
}

private fun EventContent.previewText(): String? = when (this) {
    is MessageContent -> type.previewText() ?: body
    is StickerContent -> bestDescription
    is PollContent -> question
    is UnableToDecryptContent -> "Unable to decrypt"
    is RedactedContent -> "Message deleted"
    else -> null
}

private fun io.element.android.libraries.matrix.api.timeline.item.event.MessageType.previewText(): String? = when (this) {
    is TextMessageType -> body
    is EmoteMessageType -> body
    is NoticeMessageType -> body
    is OtherMessageType -> body
    is MessageTypeWithAttachment -> bestDescription
    else -> null
}

private fun io.element.android.libraries.matrix.api.timeline.item.event.MessageType.formattedBody(): String? = when (this) {
    is TextMessageType -> formatted?.body
    is EmoteMessageType -> formatted?.body
    is NoticeMessageType -> formatted?.body
    is MessageTypeWithAttachment -> formattedCaption?.body
    else -> null
}

private fun EventContent.voiceMeta(): WatchVoiceMeta? {
    val voice = ((this as? MessageContent)?.type as? VoiceMessageType) ?: return null
    val duration = voice.details?.duration ?: voice.info?.duration
    val waveform = voice.details?.waveform?.map { (it * 100).toInt().coerceIn(0, 100) }.orEmpty()
    return WatchVoiceMeta(
        durationMs = duration?.inWholeMilliseconds ?: 0L,
        waveform = waveform,
        mimeType = voice.info?.mimetype ?: "audio/ogg",
        sizeBytes = voice.info?.size ?: 0L,
        audioUrl = voice.source.safeUrl.mxcToHttpDownload(),
    )
}

private fun ProfileDetails.displayName(): String? = when (this) {
    is ProfileDetails.Ready -> displayName
    else -> null
}

/**
 * Convert an `mxc://server/mediaid` URL to a standard Matrix thumbnail HTTP URL.
 * Returns the original URL unchanged if it's not an mxc:// URL.
 */
private fun String.mxcToHttpThumbnail(size: Int): String {
    if (!startsWith("mxc://")) return this
    val path = removePrefix("mxc://")
    val parts = path.split("/", limit = 2)
    if (parts.size < 2) return this
    val server = parts[0]
    val mediaId = parts[1]
    return "https://$server/_matrix/media/v3/thumbnail/$server/$mediaId?width=$size&height=$size&method=crop"
}

/**
 * Convert an `mxc://server/mediaid` URL to a standard Matrix download HTTP URL.
 * Returns null if it's not an mxc:// URL.
 */
private fun String.mxcToHttpDownload(): String? {
    if (!startsWith("mxc://")) return null
    val path = removePrefix("mxc://")
    val parts = path.split("/", limit = 2)
    if (parts.size < 2) return null
    val server = parts[0]
    val mediaId = parts[1]
    return "https://$server/_matrix/media/v3/download/$server/$mediaId"
}