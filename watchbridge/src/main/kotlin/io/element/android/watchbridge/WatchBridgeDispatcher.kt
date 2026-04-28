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
import io.element.android.watchbridge.contract.WatchProtocol
import io.element.android.watchbridge.contract.WatchSync
import io.element.android.watchbridge.contract.WatchSyncEnvelope
import io.element.android.watchbridge.transport.WatchTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

private const val INITIAL_ROOM_LOAD_COUNT = 30
private const val MAX_AVATAR_SYNC_COUNT = 60

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

    private val inflight = LruBoundedMap<String, Job>(capacity = 64)
    private var favoritesJob: Job? = null
    private var settingsJob: Job? = null
    private val roomJobs = mutableMapOf<String, Job>()
    private val threadJobs = mutableMapOf<String, Job>()
    private val publishedAvatarKeys = mutableMapOf<String, String?>()
    private val latestAvatarKeys = mutableMapOf<String, String?>()
    private val avatarRetryJobs = mutableMapOf<String, Job>()
    private val pendingVoiceDraftCommands = ConcurrentHashMap<String, WatchCommand.UploadVoiceDraft>()
    private val pendingVoiceDraftAudio = ConcurrentHashMap<String, ByteArray>()
    private val voiceDraftJobs = ConcurrentHashMap<String, Job>()

    fun start() {
        favoritesJob?.cancel()
        favoritesJob = scope.launch {
            runCatching {
                port.ensureRoomListLoaded(INITIAL_ROOM_LOAD_COUNT)
            }.onFailure { Timber.w(it, "initial room load failed") }
            port.favorites().collectLatest { rooms ->
                Timber.d("publishing favorites snapshot count=%d", rooms.size)
                runCatching {
                    transport.publishSync(
                        path = WatchDataPaths.FAVORITES,
                        envelope = envelope(WatchSync.FavoritesSnapshot(rooms)),
                    )
                }.onFailure { Timber.w(it, "publish favorites failed") }
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
        favoritesJob?.cancel(); favoritesJob = null
        settingsJob?.cancel(); settingsJob = null
        roomJobs.values.forEach { it.cancel() }; roomJobs.clear()
        threadJobs.values.forEach { it.cancel() }; threadJobs.clear()
        avatarRetryJobs.values.forEach { it.cancel() }; avatarRetryJobs.clear()
        voiceDraftJobs.values.forEach { it.cancel() }; voiceDraftJobs.clear()
        pendingVoiceDraftCommands.clear()
        pendingVoiceDraftAudio.clear()
        latestAvatarKeys.clear()
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
        if (inflight.containsKey(cmd.requestId)) {
            // Idempotent: the caller retried; the original job will emit the final ack.
            Timber.d("Ignoring duplicate command requestId=%s", cmd.requestId)
            return
        }
        inflight.put(cmd.requestId, scope.launch { dispatch(cmd) })
    }

    private suspend fun dispatch(cmd: WatchCommand) {
        try {
            ack(WatchAck.Accepted(cmd.requestId))
            when (cmd) {
                is WatchCommand.RefreshRooms -> {
                    port.ensureRoomListLoaded(cmd.minimumCount)
                    // Collection is live via [start]; loading more triggers the next snapshot.
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
                    port.playbackDescriptor(cmd.roomId, cmd.eventId)
                        .onSuccess { ack(WatchAck.PlaybackReady(cmd.requestId, it)) }
                        .onFailure { ack(WatchAck.Failed(cmd.requestId, WatchErrorCode.PLAYBACK_UNAVAILABLE, it.message)) }
                }
                is WatchCommand.MarkAsRead -> {
                    port.markAsRead(cmd.roomId, cmd.eventId)
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
                latestAvatarKeys[cmd.roomId] = summary.avatarUri
                publishAvatarUpdate(roomId = cmd.roomId, avatarKey = summary.avatarUri, allowRetry = summary.avatarUri != null)
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
                port.roomTimeline(cmd.roomId, cmd.limit).collectLatest { items ->
                    hasEmitted = true
                    initialDeltaJob.cancel()
                    // Truncate items if the full list would exceed the transport payload limit.
                    // Try the full list first, then progressively smaller subsets.
                    var toPublish = items
                    var published = false
                    while (!published && toPublish.isNotEmpty()) {
                        val toVersion = summary.timelineVersion
                        runCatching {
                            transport.publishSync(
                                path = WatchDataPaths.roomTimeline(cmd.roomId),
                                envelope = envelope(
                                    WatchSync.TimelineDelta(
                                        roomId = cmd.roomId,
                                        fromTimelineVersion = lastVersion,
                                        toTimelineVersion = toVersion,
                                        items = toPublish,
                                    ),
                                ),
                            )
                            lastVersion = toVersion
                            published = true
                        }.onFailure { e ->
                            Timber.w(e, "Timeline publish failed for room=%s items=%d, reducing", cmd.roomId, toPublish.size)
                            val reduced = toPublish.size / 2
                            toPublish = if (reduced > 0) toPublish.takeLast(reduced) else emptyList()
                        }
                    }
                    if (!published && items.isNotEmpty()) {
                        Timber.e("Unable to publish any timeline items for room=%s", cmd.roomId)
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
                var hasEmitted = false
                val initialDeltaJob = launch {
                    delay(3_000L)
                    if (!hasEmitted) {
                        Timber.d("fetchThread sending initial empty delta for %s/%s", cmd.roomId, cmd.threadRootEventId)
                        transport.publishSync(
                            path = WatchDataPaths.thread(cmd.roomId, cmd.threadRootEventId),
                            envelope = envelope(
                                WatchSync.ThreadDelta(
                                    roomId = cmd.roomId,
                                    threadRootEventId = cmd.threadRootEventId,
                                    items = emptyList(),
                                ),
                            ),
                        )
                    }
                }
                port.threadTimeline(cmd.roomId, cmd.threadRootEventId, cmd.limit).collectLatest { items ->
                    hasEmitted = true
                    initialDeltaJob.cancel()
                    transport.publishSync(
                        path = WatchDataPaths.thread(cmd.roomId, cmd.threadRootEventId),
                        envelope = envelope(
                            WatchSync.ThreadDelta(
                                roomId = cmd.roomId,
                                threadRootEventId = cmd.threadRootEventId,
                                items = items,
                            ),
                        ),
                    )
                }
            }.onFailure {
                Timber.w(it, "fetchThread failed for %s/%s", cmd.roomId, cmd.threadRootEventId)
                ack(WatchAck.Failed(cmd.requestId, classify(it), it.message))
            }
        }
    }

    private suspend fun publishAvatarUpdates(rooms: List<io.element.android.watchbridge.contract.WatchFavoriteRoom>) {
        val visibleRooms = rooms.take(MAX_AVATAR_SYNC_COUNT)
        val visibleRoomIds = visibleRooms.map { it.roomId }.toSet()
        latestAvatarKeys.keys.filterNot { it in visibleRoomIds }.forEach { roomId ->
            latestAvatarKeys.remove(roomId)
            avatarRetryJobs.remove(roomId)?.cancel()
        }
        visibleRooms.forEach { room ->
            latestAvatarKeys[room.roomId] = room.avatarUri
            publishAvatarUpdate(roomId = room.roomId, avatarKey = room.avatarUri, allowRetry = room.avatarUri != null)
        }
    }

    private suspend fun publishAvatarUpdate(roomId: String, avatarKey: String?, allowRetry: Boolean) {
        if (publishedAvatarKeys[roomId] == avatarKey) return

        if (avatarKey == null) {
            avatarRetryJobs.remove(roomId)?.cancel()
            runCatching {
                transport.publishSync(
                    path = WatchDataPaths.avatar(roomId),
                    envelope = envelope(WatchSync.AvatarUpdate(roomId = roomId, imageBytes = null)),
                )
                publishedAvatarKeys[roomId] = null
            }.onFailure { Timber.w(it, "publish avatar removal failed for room=%s", roomId) }
            return
        }

        val bytes = port.roomAvatarThumbnail(roomId)
            .onFailure { Timber.w(it, "avatar thumbnail load failed for room=%s", roomId) }
            .getOrNull()

        if (bytes == null) {
            if (allowRetry) {
                scheduleAvatarRetry(roomId = roomId, avatarKey = avatarKey)
            }
            return
        }

        runCatching {
            transport.publishSync(
                path = WatchDataPaths.avatar(roomId),
                envelope = envelope(WatchSync.AvatarUpdate(roomId = roomId, imageBytes = bytes)),
            )
            publishedAvatarKeys[roomId] = avatarKey
        }.onFailure {
            Timber.w(it, "publish avatar failed for room=%s", roomId)
            if (allowRetry) {
                scheduleAvatarRetry(roomId = roomId, avatarKey = avatarKey)
            }
        }
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

    private fun ack(ack: WatchAck) {
        scope.launch {
            runCatching {
                transport.sendMessage(
                    path = WatchDataPaths.ACK,
                    envelope = envelope(ack),
                )
            }.onFailure { Timber.w(it, "ack send failed") }
        }
    }

    private fun envelope(payload: io.element.android.watchbridge.contract.WatchPayload) =
        WatchSyncEnvelope(generatedAtMs = clock(), payload = payload)

    private fun classify(t: Throwable): WatchErrorCode = when (t) {
        is IllegalArgumentException -> WatchErrorCode.VALIDATION
        is NoSuchElementException -> WatchErrorCode.NOT_FOUND
        is IOException -> WatchErrorCode.NETWORK
        is SecurityException -> WatchErrorCode.PERMISSION_DENIED
        else -> WatchErrorCode.UNKNOWN
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
        @Synchronized fun containsKey(k: K): Boolean = map.containsKey(k)
    }

    companion object {
        /** Parse bytes arriving from a `MessageClient`/`DataClient` path into an envelope. */
        fun parse(bytes: ByteArray): WatchSyncEnvelope = WatchBridgeSerialization.decodeEnvelopeFromBytes(bytes)
    }
}
