/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.watchbridge

import io.element.android.watchbridge.contract.WatchAck
import io.element.android.watchbridge.contract.WatchBridgeSerialization
import io.element.android.watchbridge.contract.WatchCommand
import io.element.android.watchbridge.contract.WatchDataPaths
import io.element.android.watchbridge.contract.WatchErrorCode
import io.element.android.watchbridge.contract.WatchFavoriteRoom
import io.element.android.watchbridge.contract.WatchProtocol
import io.element.android.watchbridge.contract.WatchRoomKind
import io.element.android.watchbridge.contract.WatchRoomSummary
import io.element.android.watchbridge.contract.WatchSync
import io.element.android.watchbridge.contract.WatchSyncEnvelope
import io.element.android.watchbridge.transport.WatchTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

private const val INITIAL_ROOM_LOAD_COUNT = 30
private const val MAX_AVATAR_SYNC_COUNT = 60
private const val MAX_CONCURRENT_MEDIA_PREVIEW_LOADS = 2
private const val MEDIA_PREVIEW_TTL_MS = 7L * 24L * 60L * 60L * 1000L

/**
 * The phone-side command dispatcher.
 *
 * Wires incoming [WatchCommand]s to [ElementXWatchPort] use-cases and publishes [WatchAck]s back.
 * Also kicks off the phone -> watch sync streams (favorites, per-room timeline, threads).
 *
 * All dispatch is idempotent by `requestId` via a small in-memory LRU — repeated commands with the
 * same id are coalesced to their original result.
 */
class WatchBridgeDispatcher(
    private val port: ElementXWatchPort,
    private val transport: WatchTransport,
    private val scope: CoroutineScope,
    private val settingsStore: WatchCompanionSettingsStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val requests = LruBoundedMap<String, RequestState>(capacity = 64)
    private var favoritesJob: Job? = null
    private var settingsJob: Job? = null
    private val roomJobs = mutableMapOf<String, Job>()
    private val threadJobs = mutableMapOf<String, Job>()
    private val publishedAvatarKeys = mutableMapOf<String, String?>()
    private val latestAvatarKeys = mutableMapOf<String, String?>()
    private var latestVisibleRooms = emptyList<WatchFavoriteRoom>()
    private val avatarRetryJobs = mutableMapOf<String, Job>()
    private val pendingVoiceDraftCommands = ConcurrentHashMap<String, WatchCommand.UploadVoiceDraft>()
    private val pendingVoiceDraftAudio = ConcurrentHashMap<String, ByteArray>()
    private val voiceDraftJobs = ConcurrentHashMap<String, Job>()
    private val mediaPreviewLoadSemaphore = Semaphore(MAX_CONCURRENT_MEDIA_PREVIEW_LOADS)

    fun start() {
        favoritesJob?.cancel()
        favoritesJob = scope.launch {
            runCatching {
                port.ensureRoomListLoaded(INITIAL_ROOM_LOAD_COUNT)
            }.onFailure { Timber.w(it, "initial room load failed") }
            port.favorites().collectLatest { rooms ->
                latestVisibleRooms = rooms.take(MAX_AVATAR_SYNC_COUNT)
                Timber.d("publishing favorites snapshot count=%d", rooms.size)
                publishFavoritesSnapshot(rooms = rooms, urgent = false)
                publishAvatarUpdates(rooms)
            }
        }
        // Publish settings to the watch whenever they change.
        settingsJob?.cancel()
        settingsJob = settingsStore?.let { store ->
            scope.launch {
                store.settings.collectLatest { settings ->
                    Timber.d("publishing companion settings to watch")
                    runCatching {
                        transport.publishSync(
                            path = WatchDataPaths.SETTINGS,
                            envelope = envelope(WatchSync.SettingsUpdate(settings)),
                        )
                    }.onFailure { Timber.w(it, "publish settings failed") }
                }
            }
        }
    }

    fun stop() {
        favoritesJob?.cancel()
        favoritesJob = null
        settingsJob?.cancel()
        settingsJob = null
        roomJobs.values.forEach { it.cancel() }
        roomJobs.clear()
        threadJobs.values.forEach { it.cancel() }
        threadJobs.clear()
        avatarRetryJobs.values.forEach { it.cancel() }
        avatarRetryJobs.clear()
        voiceDraftJobs.values.forEach { it.cancel() }
        voiceDraftJobs.clear()
        pendingVoiceDraftCommands.clear()
        pendingVoiceDraftAudio.clear()
        latestAvatarKeys.clear()
        latestVisibleRooms = emptyList()
    }

    fun onVoiceDraftAudio(draftId: String, audioBytes: ByteArray) {
        pendingVoiceDraftAudio[draftId] = audioBytes
        maybeCompleteVoiceDraft(draftId)
    }

    /** Entry point for the `WearableListenerService` after parsing an envelope from the watch. */
    fun onEnvelope(envelope: WatchSyncEnvelope) {
        if (envelope.protocolVersion < WatchProtocol.MIN_SUPPORTED_VERSION) {
            val cmd = envelope.payload as? WatchCommand ?: return
            ack(WatchAck.Unsupported(cmd.requestId, WatchProtocol.VERSION, envelope.protocolVersion))
            return
        }
        val cmd = envelope.payload as? WatchCommand ?: return
        requests.get(cmd.requestId)?.let { state ->
            val terminalAck = state.terminalAck
            if (terminalAck == null) {
                // Idempotent: the caller retried; the original job will emit the final ack.
                Timber.d("Ignoring duplicate command requestId=%s", cmd.requestId)
            } else {
                Timber.d("Replaying terminal ack for duplicate command requestId=%s", cmd.requestId)
                ack(terminalAck, rememberTerminal = false)
            }
            return
        }
        val job = scope.launch(start = CoroutineStart.LAZY) { dispatch(cmd) }
        requests.put(cmd.requestId, RequestState(job = job))
        job.start()
    }

    private suspend fun dispatch(cmd: WatchCommand) {
        try {
            ack(WatchAck.Accepted(cmd.requestId))
            when (cmd) {
                is WatchCommand.RefreshRooms -> {
                    port.ensureRoomListLoaded(cmd.minimumCount)
                    // Recover a reset/reinstalled watch even if this phone process remembers prior publications.
                    publishFavoritesSnapshot(rooms = latestVisibleRooms, urgent = true)
                    latestVisibleRooms.forEach { room ->
                        avatarRetryJobs.remove(room.roomId)?.cancel()
                        publishedAvatarKeys.remove(room.roomId)
                    }
                    publishAvatarUpdates(latestVisibleRooms)
                    ack(WatchAck.Sent(cmd.requestId))
                }
                is WatchCommand.OpenRoom -> openRoom(cmd)
                is WatchCommand.FetchThread -> fetchThread(cmd)
                is WatchCommand.SendText -> {
                    port.sendText(cmd.roomId, cmd.threadRootEventId, cmd.inReplyToEventId, cmd.text)
                        .onSuccess { ack(WatchAck.Sent(cmd.requestId, eventId = it)) }
                        .onFailure { ack(WatchAck.Failed(cmd.requestId, classify(it), it.message)) }
                }
                is WatchCommand.SendReaction -> {
                    port.sendReaction(cmd.roomId, cmd.eventId, cmd.reactionKey)
                        .onSuccess { ack(WatchAck.Sent(cmd.requestId)) }
                        .onFailure { ack(WatchAck.Failed(cmd.requestId, classify(it), it.message)) }
                }
                is WatchCommand.UploadVoiceDraft -> {
                    pendingVoiceDraftCommands[cmd.draft.draftId] = cmd
                    ack(WatchAck.Pending(cmd.requestId, reason = "awaiting-audio-channel"))
                    maybeCompleteVoiceDraft(cmd.draft.draftId)
                }
                is WatchCommand.RequestPlayback -> {
                    port.playbackDescriptor(cmd.roomId, cmd.eventId, cmd.threadRootEventId)
                        .onSuccess { ack(WatchAck.PlaybackReady(cmd.requestId, it)) }
                        .onFailure { ack(WatchAck.Failed(cmd.requestId, WatchErrorCode.PLAYBACK_UNAVAILABLE, it.message)) }
                }
                is WatchCommand.RequestMediaPreview -> requestMediaPreview(cmd)
                is WatchCommand.Unsubscribe -> {
                    unsubscribe(cmd)
                    ack(WatchAck.Sent(cmd.requestId))
                }
                is WatchCommand.MarkAsRead -> {
                    port.markAsRead(cmd.roomId, cmd.eventId, cmd.threadRootEventId)
                        .onSuccess { ack(WatchAck.Sent(cmd.requestId)) }
                        .onFailure { ack(WatchAck.Failed(cmd.requestId, classify(it), it.message)) }
                }
            }
        } catch (t: Throwable) {
            Timber.w(t, "Dispatch failed for %s", cmd::class.simpleName)
            ack(WatchAck.Failed(cmd.requestId, classify(t), t.message))
        }
    }

    private fun maybeCompleteVoiceDraft(draftId: String) {
        val command = pendingVoiceDraftCommands[draftId] ?: return
        val audioBytes = pendingVoiceDraftAudio[draftId] ?: return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val pendingCommand = pendingVoiceDraftCommands.remove(draftId) ?: command
                val pendingAudioBytes = pendingVoiceDraftAudio.remove(draftId) ?: audioBytes
                port.sendVoiceMessage(pendingCommand.draft, pendingAudioBytes)
                    .onSuccess { eventId ->
                        ack(WatchAck.Sent(pendingCommand.requestId, eventId = eventId.takeIf { it.isNotBlank() }))
                    }
                    .onFailure {
                        ack(WatchAck.Failed(pendingCommand.requestId, classify(it), it.message))
                    }
            } finally {
                voiceDraftJobs.remove(draftId)
            }
        }
        val existingJob = voiceDraftJobs.putIfAbsent(draftId, job)
        if (existingJob != null) {
            job.cancel()
        } else {
            job.start()
        }
    }

    private suspend fun requestMediaPreview(cmd: WatchCommand.RequestMediaPreview) {
        val dataPath = WatchDataPaths.mediaPreview(cmd.roomId, cmd.eventId)
        var imageBytes: ByteArray? = null

        for (attempt in 0 until 4) {
            imageBytes = mediaPreviewLoadSemaphore.withPermit {
                port.roomMediaPreview(cmd.roomId, cmd.eventId)
                    .onFailure {
                        Timber.w(
                            it,
                            "on-demand media preview load failed for room=%s event=%s attempt=%d",
                            cmd.roomId,
                            cmd.eventId,
                            attempt + 1,
                        )
                    }
                    .getOrNull()
            }
            if (imageBytes != null) {
                break
            }
            if (attempt < 3) {
                delay((attempt + 1) * 1_000L)
            }
        }

        val previewBytes = imageBytes
        if (previewBytes == null) {
            ack(
                WatchAck.Failed(
                    requestId = cmd.requestId,
                    code = WatchErrorCode.NOT_FOUND,
                    message = "media preview unavailable",
                ),
            )
            return
        }

        runCatching {
            transport.publishSync(
                path = dataPath,
                envelope = envelope(
                    payload = WatchSync.MediaPreview(
                        roomId = cmd.roomId,
                        eventId = cmd.eventId,
                        imageBytes = previewBytes,
                    ),
                    expiresAtMs = clock() + MEDIA_PREVIEW_TTL_MS,
                ),
            )
            ack(WatchAck.PayloadReady(cmd.requestId, dataPath))
        }.onFailure {
            Timber.w(it, "on-demand media preview publish failed for room=%s event=%s", cmd.roomId, cmd.eventId)
            ack(WatchAck.Failed(cmd.requestId, classify(it), it.message))
        }
    }

    private fun openRoom(cmd: WatchCommand.OpenRoom) {
        roomJobs[cmd.roomId]?.cancel()
        roomJobs[cmd.roomId] = scope.launch {
            runCatching {
                val summary = port.roomSummary(cmd.roomId)
                if (summary == null) {
                    ack(WatchAck.Failed(cmd.requestId, WatchErrorCode.NOT_FOUND, "room not found"))
                    return@runCatching
                }
                transport.publishSync(
                    path = WatchDataPaths.ROOM_SUMMARY,
                    envelope = envelope(WatchSync.RoomSummary(summary)),
                )
                val avatarKey = summary.avatarSyncKey()
                latestAvatarKeys[cmd.roomId] = avatarKey
                publishAvatarUpdate(roomId = cmd.roomId, avatarKey = avatarKey, allowRetry = avatarKey != null)
                ack(WatchAck.Sent(cmd.requestId))

                // Publish an initial empty delta so the watch transitions from "loading" to "empty"
                // quickly if the timeline flow takes time to emit (e.g. encrypted rooms pending key delivery).
                var hasEmitted = false
                val initialDeltaJob = launch {
                    delay(3000L)
                    if (!hasEmitted) {
                        Timber.d("openRoom sending initial empty delta for room=%s", cmd.roomId)
                        runCatching {
                            transport.publishSync(
                                path = WatchDataPaths.roomTimeline(cmd.roomId),
                                envelope = envelope(
                                    WatchSync.TimelineDelta(
                                        roomId = cmd.roomId,
                                        fromTimelineVersion = -1L,
                                        toTimelineVersion = 0L,
                                        items = emptyList(),
                                    ),
                                ),
                            )
                        }
                    }
                }
                var lastVersion = -1L
                var lastPublishedEventIds = emptySet<String>()

                port.roomTimeline(cmd.roomId, cmd.limit).collectLatest { items ->
                    hasEmitted = true
                    initialDeltaJob.cancel()
                    if (items.isEmpty()) {
                        transport.publishSync(
                            path = WatchDataPaths.roomTimeline(cmd.roomId),
                            envelope = envelope(
                                WatchSync.TimelineDelta(
                                    roomId = cmd.roomId,
                                    fromTimelineVersion = lastVersion,
                                    toTimelineVersion = summary.timelineVersion,
                                    items = emptyList(),
                                    removedEventIds = lastPublishedEventIds.toList(),
                                ),
                            ),
                        )
                        lastVersion = summary.timelineVersion
                        lastPublishedEventIds = emptySet()
                        return@collectLatest
                    }
                    // Truncate items if the full list would exceed the transport payload limit.
                    // Try the full list first, then progressively smaller subsets.
                    var toPublish = items
                    var published = false
                    var publishedItems: List<io.element.android.watchbridge.contract.WatchTimelineItem>? = null
                    while (!published && toPublish.isNotEmpty()) {
                        val toVersion = summary.timelineVersion
                        val currentEventIds = toPublish.map { it.eventId }.toSet()
                        runCatching {
                            transport.publishSync(
                                path = WatchDataPaths.roomTimeline(cmd.roomId),
                                envelope = envelope(
                                    WatchSync.TimelineDelta(
                                        roomId = cmd.roomId,
                                        fromTimelineVersion = lastVersion,
                                        toTimelineVersion = toVersion,
                                        items = toPublish,
                                        removedEventIds = (lastPublishedEventIds - currentEventIds).toList(),
                                    ),
                                ),
                            )
                            lastVersion = toVersion
                            published = true
                            publishedItems = toPublish
                        }.onFailure { e ->
                            Timber.w(e, "Timeline publish failed for room=%s items=%d, reducing", cmd.roomId, toPublish.size)
                            val reduced = toPublish.size / 2
                            toPublish = if (reduced > 0) toPublish.takeLast(reduced) else emptyList()
                        }
                    }
                    if (!published && items.isNotEmpty()) {
                        Timber.e("Unable to publish any timeline items for room=%s", cmd.roomId)
                    } else if (publishedItems != null) {
                        val currentEventIds = publishedItems.map { it.eventId }.toSet()
                        lastPublishedEventIds = currentEventIds
                    }
                }
            }.onFailure {
                Timber.w(it, "openRoom failed for room=%s", cmd.roomId)
                ack(WatchAck.Failed(cmd.requestId, classify(it), it.message))
            }
        }
    }

    private fun fetchThread(cmd: WatchCommand.FetchThread) {
        val key = "${cmd.roomId}/${cmd.threadRootEventId}"
        threadJobs[key]?.cancel()
        threadJobs[key] = scope.launch {
            runCatching {
                ack(WatchAck.Sent(cmd.requestId))
                var lastPublishedEventIds = emptySet<String>()
                port.threadTimeline(cmd.roomId, cmd.threadRootEventId, cmd.targetEventId, cmd.limit).collectLatest { items ->
                    if (items.isEmpty()) {
                        transport.publishSync(
                            path = WatchDataPaths.thread(cmd.roomId, cmd.threadRootEventId),
                            envelope = envelope(
                                WatchSync.ThreadDelta(
                                    roomId = cmd.roomId,
                                    threadRootEventId = cmd.threadRootEventId,
                                    items = emptyList(),
                                    removedEventIds = lastPublishedEventIds.toList(),
                                ),
                            ),
                        )
                        lastPublishedEventIds = emptySet()
                        return@collectLatest
                    }
                    var toPublish = items
                    var published = false
                    var publishedItems: List<io.element.android.watchbridge.contract.WatchThreadItem>? = null
                    var lastPublishFailure: Throwable? = null
                    while (!published && toPublish.isNotEmpty()) {
                        val currentEventIds = toPublish.map { it.eventId }.toSet()
                        runCatching {
                            transport.publishSync(
                                path = WatchDataPaths.thread(cmd.roomId, cmd.threadRootEventId),
                                envelope = envelope(
                                    WatchSync.ThreadDelta(
                                        roomId = cmd.roomId,
                                        threadRootEventId = cmd.threadRootEventId,
                                        items = toPublish,
                                        removedEventIds = (lastPublishedEventIds - currentEventIds).toList(),
                                    ),
                                ),
                            )
                            published = true
                            publishedItems = toPublish
                        }.onFailure { e ->
                            lastPublishFailure = e
                            Timber.w(
                                e,
                                "Thread publish failed for room=%s thread=%s items=%d, reducing",
                                cmd.roomId,
                                cmd.threadRootEventId,
                                toPublish.size,
                            )
                            val reduced = toPublish.size / 2
                            toPublish = if (reduced > 0) toPublish.takeLast(reduced) else emptyList()
                        }
                    }
                    if (!published && items.isNotEmpty()) {
                        Timber.e("Unable to publish any thread items for room=%s thread=%s", cmd.roomId, cmd.threadRootEventId)
                        ack(WatchAck.Failed(cmd.requestId, classify(lastPublishFailure ?: IOException("thread publish failed")), lastPublishFailure?.message))
                    } else if (publishedItems != null) {
                        lastPublishedEventIds = publishedItems.map { it.eventId }.toSet()
                    }
                }
            }.onFailure {
                Timber.w(it, "fetchThread failed for %s/%s", cmd.roomId, cmd.threadRootEventId)
                ack(WatchAck.Failed(cmd.requestId, classify(it), it.message))
            }
        }
    }

    private fun unsubscribe(cmd: WatchCommand.Unsubscribe) {
        val threadRootEventId = cmd.threadRootEventId
        if (threadRootEventId == null) {
            roomJobs.remove(cmd.roomId)?.cancel()
        } else {
            threadJobs.remove("${cmd.roomId}/$threadRootEventId")?.cancel()
        }
    }

    private suspend fun publishAvatarUpdates(rooms: List<WatchFavoriteRoom>) {
        val visibleRooms = rooms.take(MAX_AVATAR_SYNC_COUNT)
        val visibleRoomIds = visibleRooms.map { it.roomId }.toSet()
        latestAvatarKeys.keys.filterNot { it in visibleRoomIds }.forEach { roomId ->
            latestAvatarKeys.remove(roomId)
            avatarRetryJobs.remove(roomId)?.cancel()
        }
        visibleRooms.forEach { room ->
            val avatarKey = room.avatarSyncKey()
            latestAvatarKeys[room.roomId] = avatarKey
            publishAvatarUpdate(roomId = room.roomId, avatarKey = avatarKey, allowRetry = avatarKey != null)
        }
    }

    private suspend fun publishFavoritesSnapshot(rooms: List<WatchFavoriteRoom>, urgent: Boolean) {
        runCatching {
            transport.publishSync(
                path = WatchDataPaths.FAVORITES,
                envelope = envelope(WatchSync.FavoritesSnapshot(rooms)),
                urgent = urgent,
            )
        }.onFailure { Timber.w(it, "publish favorites failed") }
    }

    private suspend fun publishAvatarUpdate(roomId: String, avatarKey: String?, allowRetry: Boolean) {
        if (publishedAvatarKeys.containsKey(roomId) && publishedAvatarKeys[roomId] == avatarKey) return

        if (avatarKey == null) {
            avatarRetryJobs.remove(roomId)?.cancel()
            publishAvatarRemoval(roomId)
            return
        }

        val bytes = port.roomAvatarThumbnail(roomId)
            .onFailure { Timber.w(it, "avatar thumbnail load failed for room=%s", roomId) }
            .getOrNull()

        if (bytes == null) {
            publishAvatarRemoval(roomId)
            if (allowRetry) {
                scheduleAvatarRetry(roomId = roomId, avatarKey = avatarKey)
            }
            return
        }

        runCatching {
            transport.publishSync(
                path = WatchDataPaths.avatar(roomId),
                envelope = envelope(WatchSync.AvatarUpdate(roomId = roomId, imageBytes = bytes)),
                urgent = true,
            )
            publishedAvatarKeys[roomId] = avatarKey
        }.onFailure {
            Timber.w(it, "publish avatar failed for room=%s", roomId)
            if (allowRetry) {
                scheduleAvatarRetry(roomId = roomId, avatarKey = avatarKey)
            }
        }
    }

    private suspend fun publishAvatarRemoval(roomId: String) {
        runCatching {
            transport.publishSync(
                path = WatchDataPaths.avatar(roomId),
                envelope = envelope(WatchSync.AvatarUpdate(roomId = roomId, imageBytes = null)),
                urgent = true,
            )
            publishedAvatarKeys[roomId] = null
        }.onFailure { Timber.w(it, "publish avatar removal failed for room=%s", roomId) }
    }

    private fun scheduleAvatarRetry(roomId: String, avatarKey: String) {
        if (publishedAvatarKeys[roomId] == avatarKey) return
        if (avatarRetryJobs[roomId]?.isActive == true) return

        avatarRetryJobs[roomId] = scope.launch {
            try {
                repeat(4) { attempt ->
                    delay((attempt + 1) * 1_500L)
                    if (latestAvatarKeys[roomId] != avatarKey || publishedAvatarKeys[roomId] == avatarKey) {
                        return@launch
                    }
                    publishAvatarUpdate(roomId = roomId, avatarKey = avatarKey, allowRetry = false)
                    if (publishedAvatarKeys[roomId] == avatarKey) {
                        return@launch
                    }
                }
            } finally {
                avatarRetryJobs.remove(roomId)
            }
        }
    }

    private fun WatchFavoriteRoom.avatarSyncKey(): String? =
        avatarUri ?: roomId.takeIf { kind == WatchRoomKind.DM }

    private fun WatchRoomSummary.avatarSyncKey(): String? =
        avatarUri ?: roomId.takeIf { kind == WatchRoomKind.DM }

    private fun ack(ack: WatchAck, rememberTerminal: Boolean = true) {
        if (rememberTerminal && ack.isTerminal()) {
            val existing = requests.get(ack.requestId)
            requests.put(
                ack.requestId,
                RequestState(
                    job = existing?.job,
                    terminalAck = ack,
                ),
            )
        }
        scope.launch {
            runCatching {
                transport.sendMessage(
                    path = WatchDataPaths.ACK,
                    envelope = envelope(ack),
                )
            }.onFailure { Timber.w(it, "ack send failed") }
        }
    }

    private fun envelope(
        payload: io.element.android.watchbridge.contract.WatchPayload,
        expiresAtMs: Long? = null,
    ) = WatchSyncEnvelope(generatedAtMs = clock(), expiresAtMs = expiresAtMs, payload = payload)

    private fun classify(t: Throwable): WatchErrorCode = when (t) {
        is IllegalArgumentException -> WatchErrorCode.VALIDATION
        is NoSuchElementException -> WatchErrorCode.NOT_FOUND
        is IOException -> WatchErrorCode.NETWORK
        is SecurityException -> WatchErrorCode.PERMISSION_DENIED
        else -> WatchErrorCode.UNKNOWN
    }

    private data class RequestState(
        val job: Job? = null,
        val terminalAck: WatchAck? = null,
    )

    private fun WatchAck.isTerminal(): Boolean = when (this) {
        is WatchAck.Accepted,
        is WatchAck.Pending -> false
        is WatchAck.Failed,
        is WatchAck.PayloadReady,
        is WatchAck.PlaybackReady,
        is WatchAck.Sent,
        is WatchAck.Unsupported -> true
    }

    // Tiny LRU without guava dependency.
    private class LruBoundedMap<K, V>(private val capacity: Int) {
        private val map = linkedMapOf<K, V>()
        @Synchronized fun put(k: K, v: V) {
            map.remove(k)
            map[k] = v
            while (map.size > capacity) {
                val it = map.keys.iterator()
                it.next()
                it.remove()
            }
        }
        @Synchronized fun get(k: K): V? = map[k]
    }

    companion object {
        /** Parse bytes arriving from a `MessageClient`/`DataClient` path into an envelope. */
        fun parse(bytes: ByteArray): WatchSyncEnvelope = WatchBridgeSerialization.decodeEnvelopeFromBytes(bytes)
    }
}
