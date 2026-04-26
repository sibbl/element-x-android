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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber

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
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val inflight = LruBoundedMap<String, Job>(capacity = 64)
    private var favoritesJob: Job? = null
    private val roomJobs = mutableMapOf<String, Job>()
    private val threadJobs = mutableMapOf<String, Job>()

    fun start() {
        favoritesJob?.cancel()
        favoritesJob = scope.launch {
            port.favorites().collectLatest { rooms ->
                runCatching {
                    transport.publishSync(
                        path = WatchDataPaths.FAVORITES,
                        envelope = envelope(WatchSync.FavoritesSnapshot(rooms)),
                    )
                }.onFailure { Timber.w(it, "publish favorites failed") }
            }
        }
    }

    fun stop() {
        favoritesJob?.cancel(); favoritesJob = null
        roomJobs.values.forEach { it.cancel() }; roomJobs.clear()
        threadJobs.values.forEach { it.cancel() }; threadJobs.clear()
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
                    port.sendText(cmd.roomId, cmd.threadRootEventId, cmd.text)
                        .onSuccess { ack(WatchAck.Sent(cmd.requestId, eventId = it)) }
                        .onFailure { ack(WatchAck.Failed(cmd.requestId, classify(it), it.message)) }
                }
                is WatchCommand.SendReaction -> {
                    port.sendReaction(cmd.roomId, cmd.eventId, cmd.reactionKey)
                        .onSuccess { ack(WatchAck.Sent(cmd.requestId)) }
                        .onFailure { ack(WatchAck.Failed(cmd.requestId, classify(it), it.message)) }
                }
                is WatchCommand.UploadVoiceDraft -> {
                    ack(WatchAck.Pending(cmd.requestId, reason = "awaiting-audio-channel"))
                    // Actual bytes arrive on the Channel; the channel handler completes the send and
                    // emits the final ack. See VoiceChannelBridge in the host app integration.
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

    private fun openRoom(cmd: WatchCommand.OpenRoom) {
        roomJobs[cmd.roomId]?.cancel()
        roomJobs[cmd.roomId] = scope.launch {
            val summary = port.roomSummary(cmd.roomId)
            if (summary == null) {
                ack(WatchAck.Failed(cmd.requestId, WatchErrorCode.NOT_FOUND, "room not found"))
                return@launch
            }
            transport.publishSync(
                path = WatchDataPaths.ROOM_SUMMARY,
                envelope = envelope(WatchSync.RoomSummary(summary)),
            )
            var lastVersion = -1L
            port.roomTimeline(cmd.roomId, cmd.limit).collectLatest { items ->
                val toVersion = summary.timelineVersion
                transport.publishSync(
                    path = WatchDataPaths.roomTimeline(cmd.roomId),
                    envelope = envelope(
                        WatchSync.TimelineDelta(
                            roomId = cmd.roomId,
                            fromTimelineVersion = lastVersion,
                            toTimelineVersion = toVersion,
                            items = items,
                        ),
                    ),
                )
                lastVersion = toVersion
            }
            ack(WatchAck.Sent(cmd.requestId))
        }
    }

    private fun fetchThread(cmd: WatchCommand.FetchThread) {
        val key = "${cmd.roomId}/${cmd.threadRootEventId}"
        threadJobs[key]?.cancel()
        threadJobs[key] = scope.launch {
            port.threadTimeline(cmd.roomId, cmd.threadRootEventId, cmd.limit).collectLatest { items ->
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
            ack(WatchAck.Sent(cmd.requestId))
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
