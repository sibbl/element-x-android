/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.watchbridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.BitmapFactory
import android.util.Base64
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import io.element.android.libraries.androidutils.bitmap.calculateInSampleSize
import io.element.android.libraries.androidutils.bitmap.resizeToMax
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.di.DependencyInjectionGraphOwner
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.media.AudioInfo
import io.element.android.libraries.matrix.api.media.ImageInfo
import io.element.android.libraries.matrix.api.media.MatrixMediaLoader
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.room.CreateTimelineParams
import io.element.android.libraries.matrix.api.room.CurrentUserMembership
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.room.RoomInfo
import io.element.android.libraries.matrix.api.room.RoomMember
import io.element.android.libraries.matrix.api.room.roomMembers
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
import io.element.android.libraries.matrix.api.timeline.item.event.StickerMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.UnableToDecryptContent
import io.element.android.libraries.matrix.api.timeline.item.event.VideoMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.VoiceMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.toEventOrTransactionId
import io.element.android.libraries.matrix.api.timeline.item.virtual.VirtualTimelineItem
import io.element.android.libraries.matrix.ui.model.getAvatarData
import io.element.android.libraries.push.api.notifications.NotificationCleaner
import io.element.android.libraries.push.api.notifications.conversations.NotificationConversationShortcutRoom
import io.element.android.services.appnavstate.api.currentSessionId
import io.element.android.watchbridge.ElementXWatchPort
import io.element.android.watchbridge.WatchBridgeDispatcher
import io.element.android.watchbridge.WatchCompanionSettingsStore
import io.element.android.watchbridge.contract.WatchDataPaths
import io.element.android.watchbridge.contract.WatchFavoriteRoom
import io.element.android.watchbridge.contract.WatchMediaPreview
import io.element.android.watchbridge.contract.WatchPlaybackDescriptor
import io.element.android.watchbridge.contract.WatchProtocol
import io.element.android.watchbridge.contract.WatchReactionSummary
import io.element.android.watchbridge.contract.WatchReactionSender
import io.element.android.watchbridge.contract.WatchRoomKind
import io.element.android.watchbridge.contract.WatchRoomSummary
import io.element.android.watchbridge.contract.WatchSync
import io.element.android.watchbridge.contract.WatchSyncEnvelope
import io.element.android.watchbridge.contract.WatchThreadItem
import io.element.android.watchbridge.contract.WatchTimelineItem
import io.element.android.watchbridge.contract.WatchTimelineItemKind
import io.element.android.watchbridge.contract.WatchVoiceDraft
import io.element.android.watchbridge.contract.WatchVoiceMeta
import io.element.android.watchbridge.transport.PlayServicesWatchTransport
import io.element.android.x.di.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds

private const val ROOM_LIST_PAGE_SIZE = 30
private const val MAX_ROOM_LIST_COUNT = 200
private const val MAX_LOAD_MORE_ATTEMPTS = 8
private const val WATCH_AVATAR_SIZE_PX = 64L
private const val WATCH_MEDIA_PREVIEW_SIZE_PX = 384L
private const val MAX_WATCH_PLAYBACK_BYTES = 64 * 1024
private const val MAX_FAVORITE_PREVIEW_TEXT_LENGTH = 180
private const val MAX_WATCH_REACTION_MEMBERS = 1_000
private val FAVORITE_PREVIEW_WHITESPACE_REGEX = "\\s+".toRegex()
private const val WATCH_RUNTIME_PREFS = "element_x_watchbridge_runtime"
private const val KEY_LAST_SESSION_ID = "last_session_id"

internal data class TimelineProjection(
    val items: List<WatchTimelineItem>,
    val mediaSources: Map<String, MediaPreviewSourceRef>,
)

internal data class ThreadProjection(
    val items: List<WatchThreadItem>,
    val mediaSources: Map<String, MediaPreviewSourceRef>,
)

internal data class MediaPreviewSourceRef(
    val primarySource: MediaSource,
    val thumbnailSource: MediaSource? = null,
)

/** Process-wide runtime that keeps the Wear bridge attached to the current Matrix session. */
object ElementXWatchBridgeRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private var activeBridge: ActiveBridge? = null
    private var hasClearedForMissingSession = false
    private var _settingsStore: WatchCompanionSettingsStore? = null

    /** Lazily initialized settings store. Safe to access from any thread. */
    fun settingsStore(context: Context): WatchCompanionSettingsStore {
        _settingsStore?.let { return it }
        return synchronized(this) {
            _settingsStore ?: WatchCompanionSettingsStore(context.applicationContext).also { _settingsStore = it }
        }
    }

    fun start(context: Context, startupDelayMs: Long = 0L) {
        val appContext = context.applicationContext
        scope.launch {
            if (startupDelayMs > 0L) {
                delay(startupDelayMs)
            }
            runCatching { getOrCreateDispatcher(appContext) }
                .onFailure { Timber.e(it, "WatchBridge startup failed") }
        }
    }

    fun dispatch(context: Context, envelope: io.element.android.watchbridge.contract.WatchSyncEnvelope) {
        val appContext = context.applicationContext
        scope.launch {
            runCatching {
                val dispatcher = getOrCreateDispatcher(appContext)
                if (dispatcher == null) {
                    Timber.w("WatchBridge command ignored because no logged-in Matrix session is available")
                } else {
                    dispatcher.onEnvelope(envelope)
                }
            }.onFailure { Timber.e(it, "WatchBridge dispatch failed") }
        }
    }

    fun dispatchVoiceDraftAudio(context: Context, draftId: String, audioBytes: ByteArray) {
        val appContext = context.applicationContext
        scope.launch {
            runCatching {
                val dispatcher = getOrCreateDispatcher(appContext)
                if (dispatcher == null) {
                    Timber.w("WatchBridge voice draft ignored because no logged-in Matrix session is available")
                } else {
                    dispatcher.onVoiceDraftAudio(draftId, audioBytes)
                }
            }.onFailure { Timber.e(it, "WatchBridge voice draft dispatch failed") }
        }
    }

    fun receiveVoiceDraftAudioChannel(context: Context, channel: ChannelClient.Channel) {
        val appContext = context.applicationContext
        val draftId = WatchDataPaths.voiceDraftId(channel.path, appContext.packageName)
        val channelClient = Wearable.getChannelClient(appContext)
        scope.launch(Dispatchers.IO) {
            try {
                if (draftId == null) {
                    Timber.d("Ignoring voice channel for another app variant or non-voice path=%s", channel.path)
                    return@launch
                }
                val audioBytes = channelClient.getInputStream(channel).await().use { input -> input.readBytes() }
                Timber.d("WatchBridge listener received voice draft=%s bytes=%d", draftId, audioBytes.size)
                dispatchVoiceDraftAudio(appContext, draftId, audioBytes)
            } catch (failure: Throwable) {
                Timber.w(failure, "Failed to receive watch voice draft path=%s", channel.path)
            } finally {
                runCatching { channelClient.close(channel).await() }
                    .onFailure { Timber.w(it, "Failed to close watch voice channel path=%s", channel.path) }
            }
        }
    }

    fun visibleWatchRooms(context: Context): Flow<List<WatchFavoriteRoom>> = flow {
        val appContext = context.applicationContext
        val port = createRoomListWatchPort(appContext)
        if (port == null) {
            emit(emptyList())
            return@flow
        }
        port.ensureRoomListLoaded(ROOM_LIST_PAGE_SIZE * 2)
        emitAll(port.favorites())
    }.flowOn(Dispatchers.Default)

    internal suspend fun clearSessionState(context: Context, sessionId: SessionId) = mutex.withLock {
        val appContext = context.applicationContext
        activeBridge
            ?.takeIf { it.sessionId == sessionId }
            ?.let { bridge ->
                bridge.stop()
                activeBridge = null
            }
        if (rememberedSessionId(appContext) == sessionId.value) {
            rememberSessionId(appContext, null)
        }
        hasClearedForMissingSession = true
        clearWatchState(appContext, "session removed ${sessionId.value}")
    }

    private suspend fun getOrCreateDispatcher(context: Context): WatchBridgeDispatcher? = mutex.withLock {
        Timber.d("WatchBridge initializing dispatcher")
        val graph = ((context.applicationContext as? DependencyInjectionGraphOwner)?.graph as? AppGraph)
            ?: return@withLock null
        val sessionId = graph.activeSessionId()
        val rememberedSessionId = rememberedSessionId(context)
        if (sessionId == null) {
            val bridge = activeBridge
            if (bridge != null || rememberedSessionId != null || !hasClearedForMissingSession) {
                activeBridge = null
                bridge?.stop()
                clearWatchState(context, "session cleared")
                rememberSessionId(context, null)
                hasClearedForMissingSession = true
            }
            return@withLock null
        }
        hasClearedForMissingSession = false
        activeBridge?.takeIf { it.sessionId == sessionId }?.let { return@withLock it.dispatcher }

        val previousSessionId = activeBridge?.sessionId?.value ?: rememberedSessionId
        if (previousSessionId != null && previousSessionId != sessionId.value) {
            activeBridge?.stop()
            activeBridge = null
            clearWatchState(context, "session changed from $previousSessionId to ${sessionId.value}")
        }
        val roomPort = createRoomListWatchPort(context) ?: return@withLock null
        val dispatcher = WatchBridgeDispatcher(
            port = roomPort,
            transport = PlayServicesWatchTransport(context),
            scope = roomPort.client.sessionCoroutineScope,
            settingsStore = settingsStore(context),
        )
        dispatcher.start()
        val launcherShortcutsJob = launchLauncherShortcutUpdates(context, sessionId, roomPort, graph)
        activeBridge = ActiveBridge(sessionId, dispatcher, launcherShortcutsJob)
        rememberSessionId(context, sessionId.value)
        Timber.d("WatchBridge dispatcher started for session=%s", sessionId.value)
        dispatcher
    }

    private fun launchLauncherShortcutUpdates(
        context: Context,
        sessionId: SessionId,
        roomPort: MatrixRoomListWatchPort,
        graph: AppGraph,
    ): Job {
        return roomPort.client.sessionCoroutineScope.launch {
            runCatching { roomPort.ensureRoomListLoaded(ROOM_LIST_PAGE_SIZE) }
                .onFailure { Timber.w(it, "Launcher shortcut room preload failed for session=%s", sessionId.value) }
            roomPort.launcherRecentRooms()
                .map { rooms ->
                    rooms
                        .map { room ->
                            NotificationConversationShortcutRoom(
                                roomId = RoomId(room.roomId),
                                roomName = room.displayName,
                                roomIsDirect = room.kind == WatchRoomKind.DM,
                                roomAvatarUrl = room.avatarUri,
                            )
                        }
                }
                .distinctUntilChanged()
                .catch { failure -> Timber.w(failure, "Launcher shortcut room updates failed for session=%s", sessionId.value) }
                .collect { rooms ->
                    graph.notificationConversationService.onRecentRoomsChanged(sessionId, rooms)
                }
        }
    }

    private suspend fun clearWatchState(context: Context, reason: String) {
        val transport = PlayServicesWatchTransport(context)
        val envelope = WatchSyncEnvelope(
            generatedAtMs = System.currentTimeMillis(),
            payload = WatchSync.FullRefresh,
        )
        Timber.d("WatchBridge clearing watch state: %s", reason)
        runCatching {
            transport.sendMessage(WatchDataPaths.FULL_REFRESH, envelope)
        }.onFailure { Timber.w(it, "WatchBridge full refresh message failed") }
        runCatching {
            transport.deleteSyncPrefix(WatchProtocol.DATA_PATH_PREFIX)
        }.onFailure { Timber.w(it, "WatchBridge Data Layer clear failed") }
    }

    private fun rememberedSessionId(context: Context): String? {
        return context.getSharedPreferences(WATCH_RUNTIME_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_SESSION_ID, null)
    }

    private fun rememberSessionId(context: Context, sessionId: String?) {
        context.getSharedPreferences(WATCH_RUNTIME_PREFS, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (sessionId == null) {
                    remove(KEY_LAST_SESSION_ID)
                } else {
                    putString(KEY_LAST_SESSION_ID, sessionId)
                }
            }
            .apply()
    }

    private suspend fun createRoomListWatchPort(context: Context): MatrixRoomListWatchPort? {
        val graph = ((context.applicationContext as? DependencyInjectionGraphOwner)?.graph as? AppGraph)
        if (graph == null) {
            Timber.e("WatchBridge graph is null")
            return null
        }
        val sessionId = graph.activeSessionId()
        if (sessionId == null) {
            Timber.e("WatchBridge sessionId is null")
            return null
        }
        val clientResult = graph.matrixClientProvider.getOrRestore(sessionId)
        if (clientResult.isFailure) {
            Timber.e(clientResult.exceptionOrNull(), "WatchBridge matrix client restoration failed")
            return null
        }
        val client = clientResult.getOrNull()
        if (client == null) {
            Timber.e("WatchBridge matrix client is null after restore")
            return null
        }
        return MatrixRoomListWatchPort(
            context = context,
            client = client,
            notificationCleaner = graph.notificationCleaner,
        )
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
        val launcherShortcutsJob: Job,
    ) {
        fun stop() {
            dispatcher.stop()
            launcherShortcutsJob.cancel()
        }
    }
}

private class MatrixRoomListWatchPort(
    private val context: Context,
    internal val client: MatrixClient,
    private val notificationCleaner: NotificationCleaner,
) : ElementXWatchPort {
    private val roomList = client.roomListService.createRoomList(
        pageSize = ROOM_LIST_PAGE_SIZE,
        source = RoomList.Source.All,
        coroutineScope = client.sessionCoroutineScope,
    )
    private val joinedRooms = ConcurrentHashMap<String, JoinedRoom>()
    private val mediaSourcesByRoom = ConcurrentHashMap<String, ConcurrentHashMap<String, MediaPreviewSourceRef>>()

    override fun favorites(): Flow<List<WatchFavoriteRoom>> = roomList.summaries
        .onEach { summaries -> subscribeToVisibleRooms(summaries, ROOM_LIST_PAGE_SIZE) }
        .map { summaries -> summaries.toWatchRooms { joinedRoom(it) } }
        .distinctUntilChanged()

    fun launcherRecentRooms(): Flow<List<WatchFavoriteRoom>> = roomList.summaries
        .onEach { summaries -> subscribeToVisibleRooms(summaries, ROOM_LIST_PAGE_SIZE) }
        .map { summaries -> summaries.toWatchRoomsInRoomListOrder { joinedRoom(it) } }
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
            avatarUri = room.watchAvatarUri(),
            kind = info.watchKind(),
            isEncrypted = info.isEncrypted == true,
            canSendMessages = true,
            timelineVersion = room.syncUpdateFlow.value,
            lastSyncTsMs = System.currentTimeMillis(),
            topic = info.topic,
            isFavorite = info.isFavorite,
        )
    }

    override fun roomTimeline(roomId: String, limit: Int): Flow<List<WatchTimelineItem>> = flow {
        val room = joinedRoom(roomId) ?: return@flow
        room.subscribeToSync()
        val timeline = room.liveTimeline
        val initiallyResolvedReactionSenderNames = room.getMembers(limit = MAX_WATCH_REACTION_MEMBERS)
            .getOrDefault(emptyList())
            .toWatchReactionSenderNames()
        coroutineScope {
            // Backward pagination can be slow for encrypted / media-heavy rooms; do it alongside
            // the live projection so the watch gets the current slice without waiting for it.
            val paginationJob = launch {
                runCatching { timeline.paginate(io.element.android.libraries.matrix.api.timeline.Timeline.PaginationDirection.BACKWARDS) }
                    .onFailure { Timber.d(it, "WatchBridge initial back-paginate for room=%s", roomId) }
            }
            try {
                emitAll(
                    timeline.timelineItems.map { items ->
                        val roomInfo = room.info()
                        val reactionSenderNames = initiallyResolvedReactionSenderNames +
                            room.membersStateFlow.value.roomMembers().orEmpty().toWatchReactionSenderNames()
                        val projection = items.toWatchTimelineProjection(
                            roomId = roomId,
                            limit = limit,
                            pinnedEventIds = roomInfo.pinnedEventIds.mapTo(mutableSetOf()) { it.value },
                            reactionSenderNames = reactionSenderNames,
                        )
                        mediaSourcesForRoom(roomId).putAll(projection.mediaSources)
                        projection.items
                    }
                )
            } finally {
                paginationJob.cancel()
            }
        }
    }

    override suspend fun roomMediaPreview(roomId: String, eventId: String): Result<ByteArray?> {
        val sourceRef = mediaSourcesByRoom[roomId]?.get(eventId) ?: return Result.success(null)
        return loadWatchMediaPreviewBytes(
            mediaLoader = client.matrixMediaLoader,
            sourceRef = sourceRef,
            maxDimensionPx = WATCH_MEDIA_PREVIEW_SIZE_PX.toInt(),
        )
    }

    override fun threadTimeline(roomId: String, threadRootEventId: String, limit: Int): Flow<List<WatchThreadItem>> = flow {
        val room = joinedRoom(roomId) ?: return@flow
        val timeline = room.createTimeline(CreateTimelineParams.Threaded(ThreadId(threadRootEventId))).getOrNull() ?: return@flow
        val initiallyResolvedReactionSenderNames = room.getMembers(limit = MAX_WATCH_REACTION_MEMBERS)
            .getOrDefault(emptyList())
            .toWatchReactionSenderNames()
        try {
            coroutineScope {
                val paginationJob = launch {
                    runCatching { timeline.paginate(io.element.android.libraries.matrix.api.timeline.Timeline.PaginationDirection.BACKWARDS) }
                        .onFailure { Timber.d(it, "WatchBridge initial back-paginate for thread=%s/%s", roomId, threadRootEventId) }
                }
                try {
                    emitAll(
                        timeline.timelineItems.map { items ->
                            val reactionSenderNames = initiallyResolvedReactionSenderNames +
                                room.membersStateFlow.value.roomMembers().orEmpty().toWatchReactionSenderNames()
                            val projection = items.toWatchThreadProjection(
                                roomId = roomId,
                                threadRootEventId = threadRootEventId,
                                limit = limit,
                                reactionSenderNames = reactionSenderNames,
                            )
                            mediaSourcesForRoom(roomId).putAll(projection.mediaSources)
                            projection.items
                        }
                    )
                } finally {
                    paginationJob.cancel()
                }
            }
        } finally {
            timeline.close()
        }
    }

    override suspend fun sendText(
        roomId: String,
        threadRootEventId: String?,
        inReplyToEventId: String?,
        text: String,
    ): Result<String> {
        val room = joinedRoom(roomId) ?: return Result.failure(NoSuchElementException("room not found"))
        return if (threadRootEventId == null) {
            if (inReplyToEventId == null) {
                room.liveTimeline.sendMessage(text, htmlBody = null, intentionalMentions = emptyList()).map { "" }
            } else {
                room.liveTimeline.replyMessage(
                    repliedToEventId = EventId(inReplyToEventId),
                    body = text,
                    htmlBody = null,
                    intentionalMentions = emptyList(),
                ).map { "" }
            }
        } else {
            val timeline = room.createTimeline(CreateTimelineParams.Threaded(ThreadId(threadRootEventId))).getOrThrow()
            try {
                if (inReplyToEventId == null) {
                    timeline.sendMessage(text, htmlBody = null, intentionalMentions = emptyList()).map { "" }
                } else {
                    timeline.replyMessage(
                        repliedToEventId = EventId(inReplyToEventId),
                        body = text,
                        htmlBody = null,
                        intentionalMentions = emptyList(),
                    ).map { "" }
                }
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
        val room = joinedRoom(draft.roomId) ?: return Result.failure(NoSuchElementException("room not found"))
        return sendWatchVoiceMessage(
            context = context,
            room = room,
            draft = draft,
            audioBytes = audioBytes,
        )
    }

    override suspend fun playbackDescriptor(roomId: String, eventId: String, threadRootEventId: String?): Result<WatchPlaybackDescriptor> {
        val room = joinedRoom(roomId) ?: return Result.failure(NoSuchElementException("room not found"))
        val timeline = if (threadRootEventId == null) {
            room.liveTimeline
        } else {
            room.createTimeline(CreateTimelineParams.Threaded(ThreadId(threadRootEventId))).getOrThrow()
        }
        return try {
            val audio = withTimeoutOrNull(5_000.milliseconds) {
                timeline.timelineItems
                    .mapNotNull { items -> items.findPlayableAudioMessage(eventId) }
                    .first()
            } ?: return Result.failure(NoSuchElementException("audio event not found"))
            val declaredSize = audio.info?.size
            if (declaredSize != null && declaredSize > MAX_WATCH_PLAYBACK_BYTES) {
                return Result.failure(IllegalArgumentException("audio message is too large for watch playback"))
            }
            client.matrixMediaLoader.loadMediaContent(audio.source).mapCatching { bytes ->
                require(bytes.size <= MAX_WATCH_PLAYBACK_BYTES) { "audio message is too large for watch playback" }
                WatchPlaybackDescriptor(
                    eventId = eventId,
                    roomId = roomId,
                    playbackUri = "",
                    durationMs = audio.durationMs,
                    mimeType = audio.info?.mimetype ?: "audio/ogg",
                    audioBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                )
            }
        } finally {
            if (threadRootEventId != null) {
                timeline.close()
            }
        }
    }

    override suspend fun roomAvatarThumbnail(roomId: String): Result<ByteArray?> {
        val room = joinedRoom(roomId) ?: return Result.failure(NoSuchElementException("room not found"))
        val avatarUrl = room.watchAvatarData().url ?: return Result.success(null)
        return loadWatchAvatarBytes(client.matrixMediaLoader, avatarUrl)
    }

    override suspend fun markAsRead(roomId: String, eventId: String, threadRootEventId: String?): Result<Unit> {
        val matrixRoomId = RoomId(roomId)
        val threadId = threadRootEventId?.let(::ThreadId)
        clearPhoneMessageNotification(roomId = matrixRoomId, threadId = threadId)
        val room = joinedRoom(roomId) ?: return Result.failure(NoSuchElementException("room not found"))
        return if (threadId == null) {
            room.liveTimeline.sendReadReceipt(EventId(eventId), ReceiptType.READ_PRIVATE)
        } else {
            val timeline = room.createTimeline(CreateTimelineParams.Threaded(threadId)).getOrThrow()
            try {
                timeline.sendReadReceipt(EventId(eventId), ReceiptType.READ_PRIVATE)
            } finally {
                timeline.close()
            }
        }
    }

    private fun clearPhoneMessageNotification(roomId: RoomId, threadId: ThreadId?) {
        if (threadId == null) {
            notificationCleaner.clearMessagesForRoom(client.sessionId, roomId)
        } else {
            notificationCleaner.clearMessagesForThread(client.sessionId, roomId, threadId)
        }
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

    private fun mediaSourcesForRoom(roomId: String): ConcurrentHashMap<String, MediaPreviewSourceRef> =
        mediaSourcesByRoom.getOrPut(roomId) { ConcurrentHashMap() }
}

internal suspend fun sendWatchVoiceMessage(
    context: Context,
    room: JoinedRoom,
    draft: WatchVoiceDraft,
    audioBytes: ByteArray,
): Result<String> {
    return runCatching {
        require(audioBytes.isNotEmpty()) { "voice draft is empty" }
        val audioFile = File.createTempFile("watch_voice_${draft.draftId}_", ".ogg", context.cacheDir).apply {
            writeBytes(audioBytes)
        }
        val audioInfo = AudioInfo(
            duration = draft.durationMs.takeIf { it > 0L }?.milliseconds,
            size = draft.sizeBytes.takeIf { it > 0L } ?: audioBytes.size.toLong(),
            mimetype = draft.mimeType,
        )
        val waveform = draft.waveform
            .ifEmpty { listOf(32, 48, 64, 52, 36) }
            .map { (it.coerceIn(0, 100) / 100f) }

        val threadRootEventId = draft.threadRootEventId
        val timeline = if (threadRootEventId == null) {
            room.liveTimeline
        } else {
            room.createTimeline(CreateTimelineParams.Threaded(ThreadId(threadRootEventId))).getOrThrow()
        }
        try {
            val uploadHandler = timeline.sendVoiceMessage(
                file = audioFile,
                audioInfo = audioInfo,
                waveform = waveform,
                inReplyToEventId = draft.inReplyToEventId?.let(::EventId),
            ).getOrThrow()
            uploadHandler.await().getOrThrow()
            ""
        } finally {
            if (threadRootEventId != null) {
                timeline.close()
            }
            if (!audioFile.delete() && audioFile.exists()) {
                Timber.w("WatchBridge voice draft temp file could not be deleted")
            }
        }
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) },
    )
}

private suspend fun List<RoomSummary>.toWatchRooms(resolveJoinedRoom: suspend (String) -> JoinedRoom?): List<WatchFavoriteRoom> {
    val rooms = filter { it.isWatchVisibleRoom() }
    val favorites = mutableListOf<WatchFavoriteRoom>()
    val recents = mutableListOf<WatchFavoriteRoom>()
    rooms.forEach { summary ->
        val projected = summary.toWatchRoom(resolveJoinedRoom)
        if (summary.info.isFavorite) {
            favorites += projected
        } else {
            recents += projected
        }
    }
    return favorites + recents
}

private suspend fun List<RoomSummary>.toWatchRoomsInRoomListOrder(resolveJoinedRoom: suspend (String) -> JoinedRoom?): List<WatchFavoriteRoom> {
    return filter { it.isWatchVisibleRoom() }
        .map { summary -> summary.toWatchRoom(resolveJoinedRoom) }
}

private fun List<MatrixTimelineItem>.findPlayableAudioMessage(eventId: String): PlayableAudioMessage? {
    return asSequence()
        .mapNotNull { it as? MatrixTimelineItem.Event }
        .firstOrNull { it.eventId?.value == eventId }
        ?.event
        ?.content
        ?.playableAudio()
}

private fun RoomSummary.isWatchVisibleRoom(): Boolean {
    return info.currentUserMembership == CurrentUserMembership.JOINED && !info.isSpace
}

private suspend fun RoomSummary.toWatchRoom(resolveJoinedRoom: suspend (String) -> JoinedRoom?): WatchFavoriteRoom {
    val unreadCount = max(info.numUnreadMessages, info.numUnreadNotifications).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val avatarUri = when {
        info.isDm -> resolveJoinedRoom(roomId.value)?.watchAvatarUri() ?: info.watchAvatarUri()
        else -> info.watchAvatarUri()
    }
    return WatchFavoriteRoom(
        roomId = roomId.value,
        displayName = info.name ?: info.canonicalAlias?.value ?: roomId.value,
        avatarUri = avatarUri,
        kind = info.watchKind(),
        unreadCount = unreadCount,
        hasMentions = info.numUnreadMentions > 0,
        lastActivityTsMs = latestEventTimestamp ?: 0L,
        lastPreviewText = latestEvent.previewText()?.compactWatchPreviewText(MAX_FAVORITE_PREVIEW_TEXT_LENGTH),
        lastPreviewKind = latestEvent.previewKind(),
        isFavorite = info.isFavorite,
    )
}

private fun RoomInfo.watchKind(): WatchRoomKind = if (isDm) WatchRoomKind.DM else WatchRoomKind.GROUP

private fun LatestEventValue.previewText(): String? = when (this) {
    LatestEventValue.None -> null
    is LatestEventValue.Local -> content.previewText()
    is LatestEventValue.Remote -> content.previewText()
    is LatestEventValue.RoomInvite -> "Invite"
}

private fun LatestEventValue.previewKind(): WatchTimelineItemKind? = when (this) {
    LatestEventValue.None,
    is LatestEventValue.RoomInvite -> null
    is LatestEventValue.Local -> content.watchKind()
    is LatestEventValue.Remote -> content.watchKind()
}

internal fun List<MatrixTimelineItem>.toWatchTimelineProjection(
    roomId: String,
    limit: Int,
    pinnedEventIds: Set<String> = emptySet(),
    reactionSenderNames: Map<String, String> = emptyMap(),
): TimelineProjection {
    val projectedItems = mutableListOf<WatchTimelineItem>()
    val mediaSources = mutableMapOf<String, MediaPreviewSourceRef>()
    var readMarkerAnchorEventId: String? = null
    var latestEventIdBeforeReadMarker: String? = null

    forEach { item ->
        when (item) {
            is MatrixTimelineItem.Event -> {
                val event = item.event
                if (event.threadInfo() is EventThreadInfo.ThreadResponse) return@forEach
                val projected = event.toWatchTimelineItem(
                    roomId = roomId,
                    isPinned = event.eventId?.value in pinnedEventIds,
                    reactionSenderNames = reactionSenderNames,
                ) ?: return@forEach
                projectedItems += projected
                latestEventIdBeforeReadMarker = projected.eventId
                event.content.mediaPreviewSourceRef()?.let { mediaSources[projected.eventId] = it }
            }
            is MatrixTimelineItem.Virtual -> {
                if (item.virtual == VirtualTimelineItem.ReadMarker) {
                    readMarkerAnchorEventId = latestEventIdBeforeReadMarker
                }
            }
            MatrixTimelineItem.Other -> Unit
        }
    }

    val limitedItems = projectedItems
        .sortedBy { it.timestampMs }
        .takeLast(limit)
    val limitedEventIds = limitedItems.map { it.eventId }.toSet()
    val anchoredEventId = when {
        readMarkerAnchorEventId != null && readMarkerAnchorEventId in limitedEventIds -> readMarkerAnchorEventId
        else -> limitedItems.lastOrNull()?.eventId
    }

    return TimelineProjection(
        items = limitedItems.map { item ->
            item.copy(isReadMarkerAnchor = item.eventId == anchoredEventId)
        },
        mediaSources = mediaSources.filterKeys { it in limitedEventIds },
    )
}

internal fun List<MatrixTimelineItem>.toWatchThreadProjection(
    roomId: String,
    threadRootEventId: String,
    limit: Int,
    reactionSenderNames: Map<String, String> = emptyMap(),
): ThreadProjection {
    val projectedItems = mutableListOf<WatchThreadItem>()
    val mediaSources = mutableMapOf<String, MediaPreviewSourceRef>()

    forEach { item ->
        val event = (item as? MatrixTimelineItem.Event)?.event ?: return@forEach
        val projected = event.toWatchThreadItem(roomId, threadRootEventId, reactionSenderNames) ?: return@forEach
        projectedItems += projected
        event.content.mediaPreviewSourceRef()?.let { mediaSources[projected.eventId] = it }
    }

    val limitedItems = projectedItems
        .sortedBy { it.timestampMs }
        .takeLast(limit)
    val limitedEventIds = limitedItems.map { it.eventId }.toSet()

    return ThreadProjection(
        items = limitedItems,
        mediaSources = mediaSources.filterKeys { it in limitedEventIds },
    )
}

private fun EventTimelineItem.toWatchThreadItem(
    roomId: String,
    threadRootEventId: String,
    reactionSenderNames: Map<String, String>,
): WatchThreadItem? {
    val eventId = eventId?.value ?: return null
    return WatchThreadItem(
        eventId = eventId,
        threadRootEventId = threadRootEventId,
        roomId = roomId,
        senderId = sender.value,
        senderDisplayName = senderProfile.displayName(),
        timestampMs = timestamp,
        kind = content.watchKind(),
        bodyText = content.previewText(),
        formattedText = (content as? MessageContent)?.type?.formattedBody(),
        isOwn = isOwn,
        reactions = watchReactions(reactionSenderNames),
        voiceMessageMeta = content.voiceMeta(),
        mediaPreview = content.mediaPreview(),
    )
}

private fun EventTimelineItem.toWatchTimelineItem(
    roomId: String,
    isPinned: Boolean = false,
    reactionSenderNames: Map<String, String> = emptyMap(),
): WatchTimelineItem? {
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
        bodyText = content.previewText(),
        formattedText = (content as? MessageContent)?.type?.formattedBody(),
        isOwn = isOwn,
        isEdited = (content as? MessageContent)?.isEdited == true,
        hasThread = threadInfo is EventThreadInfo.ThreadRoot,
        threadRootEventId = threadRootEventId,
        threadReplyCount = (threadInfo as? EventThreadInfo.ThreadRoot)?.summary?.numberOfReplies?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0,
        reactions = watchReactions(reactionSenderNames),
        voiceMessageMeta = content.voiceMeta(),
        readableByTts = content.watchKind() in setOf(WatchTimelineItemKind.TEXT, WatchTimelineItemKind.EMOTE, WatchTimelineItemKind.NOTICE),
        mediaPreview = content.mediaPreview(),
        isPinned = isPinned,
    )
}

private fun EventContent.mediaPreview(): WatchMediaPreview? = when (this) {
    is MessageContent -> when (val messageType = type) {
        is ImageMessageType -> messageType.toWatchMediaPreview()
        is StickerMessageType -> messageType.toWatchMediaPreview()
        else -> null
    }
    is StickerContent -> info.toWatchMediaPreview()
    else -> null
}

private fun EventContent.mediaPreviewSourceRef(): MediaPreviewSourceRef? = when (this) {
    is MessageContent -> when (val messageType = type) {
        is ImageMessageType -> MediaPreviewSourceRef(
            primarySource = messageType.source,
            thumbnailSource = messageType.info?.thumbnailSource,
        )
        is StickerMessageType -> MediaPreviewSourceRef(
            primarySource = messageType.source,
            thumbnailSource = messageType.info?.thumbnailSource,
        )
        else -> null
    }
    is StickerContent -> MediaPreviewSourceRef(primarySource = source)
    else -> null
}

private fun ImageMessageType.toWatchMediaPreview(): WatchMediaPreview = WatchMediaPreview(
    widthPx = info?.width.safeDimensionPx(),
    heightPx = info?.height.safeDimensionPx(),
    mimeType = info?.mimetype,
)

private fun StickerMessageType.toWatchMediaPreview(): WatchMediaPreview = WatchMediaPreview(
    widthPx = info?.width.safeDimensionPx(),
    heightPx = info?.height.safeDimensionPx(),
    mimeType = info?.mimetype,
)

private fun ImageInfo.toWatchMediaPreview(): WatchMediaPreview = WatchMediaPreview(
    widthPx = width.safeDimensionPx(),
    heightPx = height.safeDimensionPx(),
    mimeType = mimetype,
)

private fun Long?.safeDimensionPx(): Int? = this
    ?.coerceAtLeast(1L)
    ?.coerceAtMost(Int.MAX_VALUE.toLong())
    ?.toInt()

internal suspend fun loadWatchMediaPreviewBytes(
    mediaLoader: MatrixMediaLoader,
    sourceRef: MediaPreviewSourceRef,
    maxDimensionPx: Int = WATCH_MEDIA_PREVIEW_SIZE_PX.toInt(),
): Result<ByteArray?> {
    sourceRef.thumbnailSource
        ?.let { thumbnailSource ->
            normalizeWatchMediaPreviewBytes(
                bytes = mediaLoader.loadMediaContent(thumbnailSource).getOrNull(),
                maxDimensionPx = maxDimensionPx,
            )?.let { return Result.success(it) }
        }

    val generatedThumbnailBytes = mediaLoader.loadMediaThumbnail(
        source = sourceRef.primarySource,
        width = maxDimensionPx.toLong(),
        height = maxDimensionPx.toLong(),
    ).getOrNull()

    normalizeWatchMediaPreviewBytes(generatedThumbnailBytes, maxDimensionPx)?.let { return Result.success(it) }

    return mediaLoader.loadMediaContent(sourceRef.primarySource).mapCatching { bytes ->
        normalizeWatchMediaPreviewBytes(bytes, maxDimensionPx)
            ?: error("Unable to decode watch media preview")
    }
}

internal suspend fun loadWatchAvatarBytes(
    mediaLoader: MatrixMediaLoader,
    avatarUrl: String,
): Result<ByteArray?> {
    val source = MediaSource(avatarUrl)
    val thumbnailBytes = mediaLoader.loadMediaThumbnail(
        source = source,
        width = WATCH_AVATAR_SIZE_PX,
        height = WATCH_AVATAR_SIZE_PX,
    ).getOrNull()

    normalizeWatchMediaPreviewBytes(thumbnailBytes, WATCH_AVATAR_SIZE_PX.toInt())?.let { return Result.success(it) }

    return mediaLoader.loadMediaContent(source).mapCatching { bytes ->
        normalizeWatchMediaPreviewBytes(bytes, WATCH_AVATAR_SIZE_PX.toInt())
            ?: error("Unable to decode watch avatar")
    }
}

internal fun normalizeWatchMediaPreviewBytes(
    bytes: ByteArray?,
    maxDimensionPx: Int = WATCH_MEDIA_PREVIEW_SIZE_PX.toInt(),
): ByteArray? {
    if (bytes == null || bytes.isEmpty()) return null

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val decodeOptions = BitmapFactory.Options().apply {
        inSampleSize = bounds.calculateInSampleSize(maxDimensionPx, maxDimensionPx)
    }
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions) ?: return null
    val resized = decoded.resizeToMax(maxDimensionPx, maxDimensionPx)
    val format = if (resized.hasAlpha()) CompressFormat.PNG else CompressFormat.JPEG
    val quality = if (format == CompressFormat.PNG) 100 else 82

    return ByteArrayOutputStream().use { output ->
        if (!resized.compress(format, quality, output)) return null
        output.toByteArray()
    }
}

private fun List<RoomMember>.toWatchReactionSenderNames(): Map<String, String> =
    associate { member -> member.userId.value to member.disambiguatedDisplayName }

private fun EventTimelineItem.watchReactions(
    senderNames: Map<String, String> = emptyMap(),
): List<WatchReactionSummary> = reactions.map { reaction ->
    WatchReactionSummary(
        key = reaction.key,
        count = reaction.senders.size,
        reactedBySelf = false,
        senders = reaction.senders.map { sender ->
            val userId = sender.senderId.value
            WatchReactionSender(
                userId = userId,
                displayName = senderNames[userId] ?: userId,
            )
        },
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
    val audio = playableAudio() ?: return null
    return WatchVoiceMeta(
        durationMs = audio.durationMs,
        waveform = audio.waveform,
        mimeType = audio.info?.mimetype ?: "audio/ogg",
        sizeBytes = audio.info?.size ?: 0L,
        audioUrl = null,
    )
}

private fun EventContent.playableAudio(): PlayableAudioMessage? {
    val messageType = (this as? MessageContent)?.type
    return when (messageType) {
        is VoiceMessageType -> PlayableAudioMessage(
            source = messageType.source,
            info = messageType.info,
            durationMs = (messageType.details?.duration ?: messageType.info?.duration)?.inWholeMilliseconds ?: 0L,
            waveform = messageType.details?.waveform?.map { (it * 100).toInt().coerceIn(0, 100) }.orEmpty(),
        )
        is AudioMessageType -> PlayableAudioMessage(
            source = messageType.source,
            info = messageType.info,
            durationMs = messageType.info?.duration?.inWholeMilliseconds ?: 0L,
            waveform = emptyList(),
        )
        else -> null
    }
}

private data class PlayableAudioMessage(
    val source: MediaSource,
    val info: AudioInfo?,
    val durationMs: Long,
    val waveform: List<Int>,
)

private fun ProfileDetails.displayName(): String? = when (this) {
    is ProfileDetails.Ready -> displayName
    else -> null
}

internal fun RoomInfo.watchAvatarData() = if (avatarUrl == null && heroes.isNotEmpty()) {
    heroes.first().getAvatarData(AvatarSize.RoomListItem)
} else {
    getAvatarData(AvatarSize.RoomListItem)
}

private fun RoomInfo.watchAvatarUri(): String? = watchAvatarData().url?.mxcToHttpThumbnail(64)

internal suspend fun JoinedRoom.watchAvatarData() = info().let { roomInfo ->
    if (roomInfo.isDm) {
        getDirectRoomMember()
            ?.getAvatarData(AvatarSize.UserListItem)
            ?.takeIf { it.url != null }
            ?: roomInfo.watchAvatarData()
    } else {
        roomInfo.watchAvatarData()
    }
}

private suspend fun JoinedRoom.watchAvatarUri(): String? = watchAvatarData().url?.mxcToHttpThumbnail(64)

private fun Bitmap.toPngByteArray(): ByteArray = ByteArrayOutputStream().use { output ->
    compress(CompressFormat.PNG, 100, output)
    output.toByteArray()
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

internal fun String.compactWatchPreviewText(maxLength: Int): String {
    val compacted = trim().replace(FAVORITE_PREVIEW_WHITESPACE_REGEX, " ")
    return if (compacted.length <= maxLength) {
        compacted
    } else {
        compacted.take((maxLength - 3).coerceAtLeast(0)).trimEnd() + "..."
    }
}
