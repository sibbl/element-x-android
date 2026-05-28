/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.bridge

import android.content.Context
import androidx.wear.tiles.TileService
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import io.element.android.watchbridge.contract.WatchAck
import io.element.android.watchbridge.contract.WatchBridgeSerialization
import io.element.android.watchbridge.contract.WatchCommand
import io.element.android.watchbridge.contract.WatchCompanionSettings
import io.element.android.watchbridge.contract.WatchDataPaths
import io.element.android.watchbridge.contract.WatchErrorCode
import io.element.android.watchbridge.contract.WatchMessageNotification
import io.element.android.watchbridge.contract.WatchPayload
import io.element.android.watchbridge.contract.WatchProtocol
import io.element.android.watchbridge.contract.WatchSync
import io.element.android.watchbridge.contract.WatchSyncEnvelope
import io.element.android.watchbridge.contract.WatchThreadItem
import io.element.android.watchbridge.contract.WatchTimelineItem
import io.element.android.watchbridge.contract.WatchTimelineItemKind
import io.element.android.watchbridge.contract.WatchVoiceDraft
import io.element.android.wearapp.notifications.WearLocalNotificationManager
import io.element.android.wearapp.tile.FavoriteContactsTileService
import io.element.android.wearapp.tile.RecentContactsTileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val DUPLICATE_CAPABILITY_STATUS_CODE = 4006
private const val MAX_CACHED_TIMELINE_ITEMS = 100
private const val MAX_CACHED_THREAD_ITEMS = 50
private const val VOICE_UPLOAD_TIMEOUT_MS = 60_000L
private const val TILE_REFRESH_DEBOUNCE_MS = 2_000L
private const val CACHE_PERSIST_DEBOUNCE_MS = 500L
private const val TIMELINE_CACHE_PERSIST_DEBOUNCE_MS = 2_000L

internal fun mediaPreviewCacheKey(roomId: String, eventId: String): String = "$roomId/$eventId"

private infix fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean = when {
    this == null -> other == null
    other == null -> false
    else -> contentEquals(other)
}

internal fun WatchMessageNotification.toTimelineItemForOpenConversation(): WatchTimelineItem? {
    if (threadRootEventId != null) return null
    val body = bodyText?.takeIf { it.isNotBlank() } ?: return null
    return WatchTimelineItem(
        eventId = eventId,
        roomId = roomId,
        senderId = notificationSenderId(),
        senderDisplayName = senderDisplayName,
        timestampMs = timestampMs,
        kind = WatchTimelineItemKind.TEXT,
        bodyText = body,
        readableByTts = true,
    )
}

internal fun WatchMessageNotification.toThreadItemForOpenThread(): WatchThreadItem? {
    val rootEventId = threadRootEventId ?: return null
    val body = bodyText?.takeIf { it.isNotBlank() } ?: return null
    return WatchThreadItem(
        eventId = eventId,
        threadRootEventId = rootEventId,
        roomId = roomId,
        senderId = notificationSenderId(),
        senderDisplayName = senderDisplayName,
        timestampMs = timestampMs,
        kind = WatchTimelineItemKind.TEXT,
        bodyText = body,
    )
}

private fun WatchMessageNotification.notificationSenderId(): String {
    return senderDisplayName
        ?.takeIf { it.isNotBlank() }
        ?: roomDisplayName.takeIf { it.isNotBlank() }
        ?: roomId
}

/**
 * Watch-side entry point to the companion protocol.
 *
 * Wraps Google Play Services Wearable clients and surfaces
 *   - a [favorites] flow derived from phone-published `/watchbridge/favorites` `DataClient` items,
 *   - a generic [syncEvents] shared flow for per-room / per-thread subscriptions,
 *   - an [acks] shared flow so send UIs can observe their own request ids,
 *   - [send] to issue [WatchCommand]s to the phone.
 *
 * The actual Data Layer listener is declared in the manifest and forwards into [onDataChanged] /
 * [onMessageReceived].
 */
class WearBridgeClient(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cacheStore = WearBridgeCacheStore(context)
    private val localNotificationManager = WearLocalNotificationManager(context)

    private val dataClient: DataClient by lazy { Wearable.getDataClient(context) }
    private val messageClient: MessageClient by lazy { Wearable.getMessageClient(context) }
    private val channelClient: ChannelClient by lazy { Wearable.getChannelClient(context) }
    private val capabilityClient: CapabilityClient by lazy { Wearable.getCapabilityClient(context) }
    private val nodeClient: NodeClient by lazy { Wearable.getNodeClient(context) }

    private val _favorites = MutableStateFlow<List<io.element.android.watchbridge.contract.WatchFavoriteRoom>>(emptyList())
    val favorites: StateFlow<List<io.element.android.watchbridge.contract.WatchFavoriteRoom>> = _favorites.asStateFlow()

    private val _companionSettings = MutableStateFlow(WatchCompanionSettings())
    val companionSettings: StateFlow<WatchCompanionSettings> = _companionSettings.asStateFlow()

    private val _syncEvents = MutableSharedFlow<WatchPayload>(extraBufferCapacity = 64)
    val syncEvents = _syncEvents.asSharedFlow()

    private val _acks = MutableSharedFlow<WatchAck>(extraBufferCapacity = 64)
    val acks = _acks.asSharedFlow()

    private val _phoneReachable = MutableStateFlow(false)
    val phoneReachable: StateFlow<Boolean> = _phoneReachable.asStateFlow()

    private val _avatarImages = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    val avatarImages: StateFlow<Map<String, ByteArray>> = _avatarImages.asStateFlow()

    private val _mediaPreviewImages = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    val mediaPreviewImages: StateFlow<Map<String, ByteArray>> = _mediaPreviewImages.asStateFlow()
    private val mediaPreviewStateFlows = ConcurrentHashMap<String, MutableStateFlow<ByteArray?>>()
    private val pendingMediaPreviewRequests = ConcurrentHashMap.newKeySet<String>()

    /** In-memory cache of last-known timeline items per room. Survives navigation. */
    private val _timelineCache = mutableMapOf<String, CachedTimeline>()
    /** In-memory cache of last-known thread items per room/root pair. */
    private val _threadCache = mutableMapOf<String, CachedThreadTimeline>()
    /** In-memory cache of last-known room summary per room. */
    private val _summaryCache = mutableMapOf<String, io.element.android.watchbridge.contract.WatchRoomSummary>()
    /** Room subscriptions already requested during this app process. */
    private val activeRoomSubscriptions = ConcurrentHashMap.newKeySet<String>()
    /** Thread subscriptions already requested during this app process. */
    private val activeThreadSubscriptions = ConcurrentHashMap.newKeySet<String>()
    /** Latest pending cache envelope per logical cache key so bursts are coalesced. */
    private val pendingCachePersists = ConcurrentHashMap<String, WatchSyncEnvelope>()
    /** Outstanding debounce jobs for disk cache writes. */
    private val pendingCachePersistJobs = ConcurrentHashMap<String, Job>()
    /** Debounced tile refresh request to avoid hammering Wear tiles during sync bursts. */
    private var pendingTileRefreshJob: Job? = null
    /** Avoid registering listeners repeatedly if app/service startup paths re-enter [start]. */
    private var started = false
    /** Data Layer scans are deferred to foreground UI so notification wakes stay cheap. */
    private var hasPrimedDataLayer = false
    private var pendingDataLayerPrimeJob: Job? = null

    fun getCachedTimeline(roomId: String): List<io.element.android.watchbridge.contract.WatchTimelineItem> =
        _timelineCache[roomId]?.items.orEmpty()

    fun hasCachedTimelineSnapshot(roomId: String): Boolean =
        _timelineCache[roomId]?.hasSnapshot == true

    fun getCachedSummary(roomId: String): io.element.android.watchbridge.contract.WatchRoomSummary? =
        _summaryCache[roomId]

    fun getCachedThread(
        roomId: String,
        threadRootEventId: String,
    ): List<io.element.android.watchbridge.contract.WatchThreadItem> =
        _threadCache[threadCacheKey(roomId, threadRootEventId)]?.items.orEmpty()

    fun getCachedMediaPreview(roomId: String, eventId: String): ByteArray? =
        _mediaPreviewImages.value[mediaPreviewCacheKey(roomId, eventId)]

    fun getCachedAvatar(roomId: String): ByteArray? = _avatarImages.value[roomId]

    fun mediaPreviewFlow(roomId: String, eventId: String): StateFlow<ByteArray?> {
        val key = mediaPreviewCacheKey(roomId, eventId)
        return mediaPreviewStateFlows.getOrPut(key) {
            MutableStateFlow(getCachedMediaPreview(roomId, eventId))
        }.asStateFlow()
    }

    fun requestMediaPreview(roomId: String, eventId: String) {
        val key = mediaPreviewCacheKey(roomId, eventId)
        if (getCachedMediaPreview(roomId, eventId) != null) return
        if (!pendingMediaPreviewRequests.add(key)) return

        scope.launch {
            try {
                sendAwaitTerminalAck { requestId ->
                    WatchCommand.RequestMediaPreview(
                        requestId = requestId,
                        roomId = roomId,
                        eventId = eventId,
                    )
                }
            } catch (failure: Throwable) {
                Timber.w(failure, "request media preview failed for room=%s event=%s", roomId, eventId)
            } finally {
                pendingMediaPreviewRequests.remove(key)
            }
        }
    }

    fun hasCachedThreadSnapshot(roomId: String, threadRootEventId: String): Boolean =
        _threadCache[threadCacheKey(roomId, threadRootEventId)]?.hasSnapshot == true

    fun cacheTimeline(
        roomId: String,
        items: List<io.element.android.watchbridge.contract.WatchTimelineItem>,
        hasSnapshot: Boolean = true,
    ) {
        _timelineCache[roomId] = CachedTimeline(items = items, hasSnapshot = hasSnapshot)
    }

    fun cacheSummary(roomId: String, summary: io.element.android.watchbridge.contract.WatchRoomSummary) {
        _summaryCache[roomId] = summary
    }

    fun cacheThread(
        roomId: String,
        threadRootEventId: String,
        items: List<io.element.android.watchbridge.contract.WatchThreadItem>,
        hasSnapshot: Boolean = true,
    ) {
        _threadCache[threadCacheKey(roomId, threadRootEventId)] = CachedThreadTimeline(items = items, hasSnapshot = hasSnapshot)
    }

    suspend fun ensureRoomSubscription(
        roomId: String,
        limit: Int = 30,
    ) {
        val hasSnapshot = hasCachedTimelineSnapshot(roomId)
        val alreadyActive = !activeRoomSubscriptions.add(roomId)
        if (alreadyActive && hasSnapshot) return

        runCatching {
            send { requestId ->
                WatchCommand.OpenRoom(
                    requestId = requestId,
                    roomId = roomId,
                    limit = limit,
                )
            }
        }.onFailure {
            activeRoomSubscriptions.remove(roomId)
            throw it
        }
    }

    suspend fun ensureThreadSubscription(
        roomId: String,
        threadRootEventId: String,
        limit: Int = 20,
    ) {
        val subscriptionKey = threadCacheKey(roomId, threadRootEventId)
        val hasSnapshot = hasCachedThreadSnapshot(roomId, threadRootEventId)
        val alreadyActive = !activeThreadSubscriptions.add(subscriptionKey)
        if (alreadyActive && hasSnapshot) return

        runCatching {
            send { requestId ->
                WatchCommand.FetchThread(
                    requestId = requestId,
                    roomId = roomId,
                    threadRootEventId = threadRootEventId,
                    limit = limit,
                )
            }
        }.onFailure {
            activeThreadSubscriptions.remove(subscriptionKey)
            throw it
        }
    }

    fun unsubscribeRoom(roomId: String) {
        if (!activeRoomSubscriptions.remove(roomId)) return
        scope.launch { sendUnsubscribe(roomId = roomId, threadRootEventId = null) }
    }

    fun unsubscribeThread(roomId: String, threadRootEventId: String) {
        val subscriptionKey = threadCacheKey(roomId, threadRootEventId)
        if (!activeThreadSubscriptions.remove(subscriptionKey)) return
        scope.launch { sendUnsubscribe(roomId = roomId, threadRootEventId = threadRootEventId) }
    }

    private val phoneCapabilityListener = CapabilityClient.OnCapabilityChangedListener { capabilityInfo ->
        if (capabilityInfo.name == WatchProtocol.PHONE_CAPABILITY) {
            Timber.d(
                "phone capability changed reachable=%s nodes=%s",
                capabilityInfo.nodes.isNotEmpty(),
                capabilityInfo.nodes.joinToString { "${it.displayName}/${it.id}/${it.isNearby}" },
            )
            scope.launch { probePhoneCapability() }
        }
    }

    fun start() {
        if (started) return
        started = true
        capabilityClient.addLocalCapability(WatchProtocol.WATCH_CAPABILITY)
            .addOnSuccessListener { Timber.d("watch capability registered") }
            .addOnFailureListener {
                if (it.isDuplicateCapability()) {
                    Timber.d("watch capability already registered")
                } else {
                    Timber.w(it, "watch capability registration failed")
                }
            }
        capabilityClient.addListener(phoneCapabilityListener, WatchProtocol.PHONE_CAPABILITY)
            .addOnFailureListener { Timber.w(it, "phone capability listener registration failed") }
        scope.launch { probePhoneCapability() }
        scope.launch {
            primeCachedStateFromDisk()
        }
    }

    fun stop() {
        started = false
        capabilityClient.removeListener(phoneCapabilityListener)
        // Scope teardown left to Application lifecycle; explicit cancel intentionally avoided
        // here so in-flight request UIs keep observing acks on Activity restarts.
        activeRoomSubscriptions.clear()
        activeThreadSubscriptions.clear()
        pendingTileRefreshJob?.cancel()
        pendingTileRefreshJob = null
        pendingCachePersistJobs.values.forEach(Job::cancel)
        pendingCachePersistJobs.clear()
        pendingCachePersists.clear()
        pendingDataLayerPrimeJob?.cancel()
        pendingDataLayerPrimeJob = null
    }

    fun refreshPhoneReachability() {
        scope.launch { probePhoneCapability() }
    }

    fun refreshCachedStateFromDataLayer() {
        if (hasPrimedDataLayer || pendingDataLayerPrimeJob?.isActive == true) return
        pendingDataLayerPrimeJob = scope.launch {
            try {
                primeCachedStateFromDataLayer()
                hasPrimedDataLayer = true
            } finally {
                pendingDataLayerPrimeJob = null
            }
        }
    }

    /** Called from the app-level `WearableListenerService` on any `DataItem` change. */
    fun onDataChanged(events: DataEventBuffer) {
        for (ev in events) {
            val path = ev.dataItem.uri.path.orEmpty()
            if (ev.type == DataEvent.TYPE_DELETED) {
                val notificationKey = WatchDataPaths.notificationKey(path)
                if (notificationKey != null) {
                    localNotificationManager.dismiss(notificationKey)
                } else if (path.startsWith(WatchProtocol.DATA_PATH_PREFIX)) {
                    handleFullRefresh()
                }
                continue
            }
            val item = ev.dataItem
            val data = DataMapItem.fromDataItem(item).dataMap.getByteArray("envelope") ?: continue
            val envelope = decode(data) ?: continue
            dispatchIncoming(envelope)
        }
    }

    /** Called from the app-level `WearableListenerService` on an incoming `MessageClient` event. */
    fun onMessageReceived(path: String, bytes: ByteArray) {
        val envelope = decode(bytes) ?: return
        dispatchIncoming(envelope)
    }

    /** Send a command to the phone. Returns the `requestId` so callers can observe their own ack. */
    suspend fun send(builder: (String) -> WatchCommand): String {
        val requestId = UUID.randomUUID().toString()
        sendCommand(builder(requestId))
        return requestId
    }

    suspend fun sendAwaitTerminalAck(builder: (String) -> WatchCommand): WatchAck = coroutineScope {
        val requestId = UUID.randomUUID().toString()
        val ackDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            acks.filter { it.requestId == requestId }
                .first {
                    it is WatchAck.Sent ||
                        it is WatchAck.Failed ||
                        it is WatchAck.Unsupported ||
                        it is WatchAck.PayloadReady ||
                        it is WatchAck.PlaybackReady
                }
        }
        try {
            sendCommand(builder(requestId))
            when (val ack = withTimeout(WatchProtocol.DEFAULT_COMMAND_TIMEOUT_MS) { ackDeferred.await() }) {
                is WatchAck.Failed -> throw WatchCommandException(ack.code, ack.message)
                is WatchAck.Unsupported -> throw WatchCommandException(WatchErrorCode.FEATURE_DISABLED, "phone/watch versions are incompatible")
                else -> ack
            }
        } catch (failure: Throwable) {
            ackDeferred.cancel()
            if (failure is WatchCommandException) throw failure
            val code = when {
                failure.message?.contains("phone not reachable", ignoreCase = true) == true -> WatchErrorCode.PHONE_APP_UNAVAILABLE
                failure is kotlinx.coroutines.TimeoutCancellationException -> WatchErrorCode.TIMEOUT
                else -> WatchErrorCode.UNKNOWN
            }
            throw WatchCommandException(code = code, message = failure.message, cause = failure)
        }
    }

    suspend fun uploadVoiceDraftAwaitTerminalAck(
        draft: WatchVoiceDraft,
        audioFile: File,
    ): WatchAck = coroutineScope {
        val requestId = UUID.randomUUID().toString()
        val ackDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            acks.filter { it.requestId == requestId }
                .first {
                    it is WatchAck.Sent ||
                        it is WatchAck.Failed ||
                        it is WatchAck.Unsupported ||
                        it is WatchAck.PayloadReady
                }
        }
        try {
            sendCommand(
                WatchCommand.UploadVoiceDraft(
                    requestId = requestId,
                    draft = draft,
                ),
            )
            uploadVoiceDraftBytes(draftId = draft.draftId, audioFile = audioFile)
            when (val ack = withTimeout(VOICE_UPLOAD_TIMEOUT_MS) { ackDeferred.await() }) {
                is WatchAck.Failed -> throw WatchCommandException(ack.code, ack.message)
                is WatchAck.Unsupported -> throw WatchCommandException(WatchErrorCode.FEATURE_DISABLED, "phone/watch versions are incompatible")
                else -> ack
            }
        } catch (failure: Throwable) {
            ackDeferred.cancel()
            if (failure is WatchCommandException) throw failure
            val code = when {
                failure.message?.contains("phone not reachable", ignoreCase = true) == true -> WatchErrorCode.PHONE_APP_UNAVAILABLE
                failure is kotlinx.coroutines.TimeoutCancellationException -> WatchErrorCode.TIMEOUT
                failure is IOException -> WatchErrorCode.UPLOAD_FAILED
                else -> WatchErrorCode.UNKNOWN
            }
            throw WatchCommandException(code = code, message = failure.message, cause = failure)
        }
    }

    private suspend fun sendCommand(command: WatchCommand) {
        val envelope = WatchSyncEnvelope(generatedAtMs = System.currentTimeMillis(), payload = command)
        val bytes = WatchBridgeSerialization.encodeEnvelopeToBytes(envelope)
        withContext(Dispatchers.IO) {
            val node = resolvePhoneNode() ?: error("phone not reachable")
            messageClient.sendMessage(node.id, WatchDataPaths.COMMAND, bytes).await()
        }
    }

    private suspend fun sendUnsubscribe(roomId: String, threadRootEventId: String?) {
        runCatching {
            sendCommand(
                WatchCommand.Unsubscribe(
                    requestId = UUID.randomUUID().toString(),
                    roomId = roomId,
                    threadRootEventId = threadRootEventId,
                ),
            )
        }.onFailure {
            Timber.w(it, "unsubscribe failed for room=%s thread=%s", roomId, threadRootEventId)
        }
    }

    private suspend fun uploadVoiceDraftBytes(
        draftId: String,
        audioFile: File,
    ) = withContext(Dispatchers.IO) {
        val node = resolvePhoneNode() ?: error("phone not reachable")
        val channel = channelClient.openChannel(node.id, WatchDataPaths.voiceDraftChannel(draftId)).await()
        try {
            channelClient.getOutputStream(channel).await().use { output ->
                audioFile.inputStream().use { input -> input.copyTo(output) }
                output.flush()
            }
        } finally {
            channelClient.close(channel).await()
        }
    }

    private suspend fun primeCachedStateFromDataLayer() = withContext(Dispatchers.IO) {
        runCatching {
            val items = dataClient.getDataItems().await()
            for (index in 0 until items.count) {
                val item = items[index]
                val bytes = DataMapItem.fromDataItem(item).dataMap.getByteArray("envelope") ?: continue
                decode(bytes)?.let(::dispatchIncoming)
            }
            items.release()
        }.onFailure { Timber.w(it, "prime cached state failed") }
    }

    private suspend fun primeCachedStateFromDisk() = withContext(Dispatchers.IO) {
        runCatching {
            cacheStore.restoreEnvelopes().forEach { envelope ->
                dispatchIncoming(envelope, persist = false)
            }
        }.onFailure { Timber.w(it, "prime disk cache failed") }
    }

    private suspend fun probePhoneCapability() = withContext(Dispatchers.IO) {
        runCatching {
            val caps = capabilityClient
                .getCapability(WatchProtocol.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .await()
            val connectedNodes = nodeClient.connectedNodes.await()
            val nodes = caps.nodes.takeIf { it.isNotEmpty() } ?: connectedNodes
            val reachable = nodes.isNotEmpty()
            Timber.d(
                "phone reachability probe reachable=%s capabilityNodes=%s connectedNodes=%s",
                reachable,
                caps.nodes.joinToString { it.debugLabel() },
                connectedNodes.joinToString { it.debugLabel() },
            )
            if (!reachable) {
                activeRoomSubscriptions.clear()
                activeThreadSubscriptions.clear()
            }
            _phoneReachable.value = reachable
        }.onFailure {
            _phoneReachable.value = false
            activeRoomSubscriptions.clear()
            activeThreadSubscriptions.clear()
            Timber.w(it, "phone capability probe failed")
        }
    }

    private suspend fun resolvePhoneNode(): Node? {
        val capabilityNodes = capabilityClient
            .getCapability(WatchProtocol.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .await()
            .nodes
        val nodes = capabilityNodes.takeIf { it.isNotEmpty() } ?: nodeClient.connectedNodes.await()
        return nodes.nearbyFirst()
    }

    private fun Iterable<Node>.nearbyFirst(): Node? = firstOrNull { it.isNearby } ?: firstOrNull()

    private fun Node.debugLabel(): String = "$displayName/$id/$isNearby"

    private fun Throwable.isDuplicateCapability(): Boolean =
        this is ApiException && statusCode == DUPLICATE_CAPABILITY_STATUS_CODE

    private fun dispatchIncoming(envelope: WatchSyncEnvelope, persist: Boolean = true) {
        if (envelope.protocolVersion < WatchProtocol.MIN_SUPPORTED_VERSION) {
            Timber.w("dropping unsupported envelope v=%d", envelope.protocolVersion)
            return
        }
        if (persist && envelope.payload !is WatchSync.FullRefresh) {
            scheduleCachePersist(envelope)
        }
        when (val p = envelope.payload) {
            is WatchSync.FavoritesSnapshot -> {
                Timber.d("received favorites snapshot count=%d", p.rooms.size)
                if (_favorites.value != p.rooms) {
                    _favorites.value = p.rooms
                    requestTileRefresh()
                }
            }
            is WatchSync.SettingsUpdate -> {
                Timber.d("received companion settings update")
                if (_companionSettings.value != p.settings) {
                    _companionSettings.value = p.settings
                    requestTileRefresh()
                }
            }
            is WatchSync.AvatarUpdate -> {
                Timber.d("received avatar update roomId=%s bytes=%s", p.roomId, p.imageBytes?.size ?: 0)
                val imageBytes = p.imageBytes
                val previousImageBytes = _avatarImages.value[p.roomId]
                if (previousImageBytes contentEqualsNullable imageBytes) return
                _avatarImages.value = _avatarImages.value.toMutableMap().apply {
                    if (imageBytes == null) remove(p.roomId) else put(p.roomId, imageBytes)
                }
                requestTileRefresh()
            }
            is WatchSync.MediaPreview -> {
                val key = mediaPreviewCacheKey(p.roomId, p.eventId)
                updateMediaPreviewState(key = key, imageBytes = p.imageBytes)
                scope.launch { _syncEvents.emit(p) }
            }
            is WatchSync.RoomSummary -> {
                _summaryCache[p.summary.roomId] = p.summary
                scope.launch { _syncEvents.emit(p) }
            }
            is WatchSync.TimelineDelta -> {
                mergeTimelineDelta(p)
                scope.launch { _syncEvents.emit(p) }
            }
            is WatchSync.ThreadDelta -> {
                mergeThreadDelta(p)
                scope.launch { _syncEvents.emit(p) }
            }
            is WatchSync.UnreadUpdate -> {
                val updatedFavorites = _favorites.value.map { room ->
                    if (room.roomId == p.roomId) {
                        room.copy(unreadCount = p.unreadCount, hasMentions = p.hasMentions)
                    } else {
                        room
                    }
                }
                if (updatedFavorites != _favorites.value) {
                    _favorites.value = updatedFavorites
                    requestTileRefresh()
                }
                scope.launch { _syncEvents.emit(p) }
            }
            is WatchSync.MessageNotification -> {
                p.notification.imagePreviewBytes?.let { imageBytes ->
                    updateMediaPreviewState(
                        key = mediaPreviewCacheKey(p.notification.roomId, p.notification.eventId),
                        imageBytes = imageBytes,
                    )
                    if (persist) {
                        scheduleCachePersist(
                            envelope.copy(
                                payload = WatchSync.MediaPreview(
                                    roomId = p.notification.roomId,
                                    eventId = p.notification.eventId,
                                    imageBytes = imageBytes,
                                ),
                            ),
                        )
                    }
                }
                val timelineDelta = p.notification.toTimelineItemForOpenConversation()?.let { item ->
                    WatchSync.TimelineDelta(
                        roomId = p.notification.roomId,
                        fromTimelineVersion = p.notification.timestampMs,
                        toTimelineVersion = p.notification.timestampMs,
                        items = listOf(item),
                    )
                }
                if (timelineDelta != null) {
                    mergeTimelineDelta(timelineDelta)
                    if (persist) {
                        scheduleCachePersist(envelope.copy(payload = timelineDelta))
                    }
                    if (shouldEmitNotificationTimelineDelta(p.notification.roomId)) {
                        scope.launch { _syncEvents.emit(timelineDelta) }
                    }
                }
                val threadDelta = p.notification.toThreadItemForOpenThread()?.let { item ->
                    WatchSync.ThreadDelta(
                        roomId = p.notification.roomId,
                        threadRootEventId = item.threadRootEventId,
                        items = listOf(item),
                    )
                }
                if (threadDelta != null) {
                    mergeThreadDelta(threadDelta)
                    if (persist) {
                        scheduleCachePersist(envelope.copy(payload = threadDelta))
                    }
                    if (shouldEmitNotificationThreadDelta(threadDelta.roomId, threadDelta.threadRootEventId)) {
                        scope.launch { _syncEvents.emit(threadDelta) }
                    }
                }
                localNotificationManager.show(
                    notification = p.notification,
                    generatedAtMs = envelope.generatedAtMs,
                    expiresAtMs = envelope.expiresAtMs,
                )
            }
            is WatchSync.Invalidation -> {
                applyInvalidation(p)
                scope.launch { _syncEvents.emit(p) }
            }
            is WatchSync.FullRefresh -> {
                handleFullRefresh()
                scope.launch { _syncEvents.emit(p) }
            }
            is WatchAck -> {
                Timber.d("received ack=%s requestId=%s", p::class.simpleName, p.requestId)
                scope.launch { _acks.emit(p) }
            }
            else -> scope.launch { _syncEvents.emit(p) }
        }
    }

    private fun shouldEmitNotificationTimelineDelta(roomId: String): Boolean {
        return roomId in activeRoomSubscriptions
    }

    private fun shouldEmitNotificationThreadDelta(roomId: String, threadRootEventId: String): Boolean {
        return threadCacheKey(roomId, threadRootEventId) in activeThreadSubscriptions
    }

    private fun mergeTimelineDelta(delta: WatchSync.TimelineDelta) {
        removeCachedMediaPreviews(roomId = delta.roomId, eventIds = delta.removedEventIds)
        val updatedItems = mergeTimelineItems(
            existing = getCachedTimeline(delta.roomId),
            incoming = delta.items,
        )
            .filter { it.eventId !in delta.removedEventIds }
            .sortedBy { it.timestampMs }
            .takeLast(MAX_CACHED_TIMELINE_ITEMS)
        cacheTimeline(roomId = delta.roomId, items = updatedItems, hasSnapshot = true)
    }

    private fun mergeThreadDelta(delta: WatchSync.ThreadDelta) {
        removeCachedMediaPreviews(roomId = delta.roomId, eventIds = delta.removedEventIds)
        val updatedItems = mergeThreadItems(
            existing = getCachedThread(delta.roomId, delta.threadRootEventId),
            incoming = delta.items,
        )
            .filter { it.eventId !in delta.removedEventIds }
            .sortedBy { it.timestampMs }
            .takeLast(MAX_CACHED_THREAD_ITEMS)
        cacheThread(
            roomId = delta.roomId,
            threadRootEventId = delta.threadRootEventId,
            items = updatedItems,
            hasSnapshot = true,
        )
    }

    private fun applyInvalidation(invalidation: WatchSync.Invalidation) {
        when (invalidation.scope) {
            WatchSync.Invalidation.InvalidationScope.FAVORITES -> {
                _favorites.value = emptyList()
                requestTileRefresh()
            }
            WatchSync.Invalidation.InvalidationScope.ROOM -> invalidation.roomId?.let { roomId ->
                _summaryCache.remove(roomId)
                _timelineCache.remove(roomId)
                activeRoomSubscriptions.remove(roomId)
                _threadCache.keys
                    .filter { it.startsWith(threadCachePrefix(roomId)) }
                    .forEach { key ->
                        _threadCache.remove(key)
                        activeThreadSubscriptions.remove(key)
                    }
                clearMediaPreviewStateForRoom(roomId)
                _avatarImages.value = _avatarImages.value.toMutableMap().apply { remove(roomId) }
                requestTileRefresh()
            }
            WatchSync.Invalidation.InvalidationScope.THREAD -> invalidation.roomId?.let { roomId ->
                _threadCache.keys
                    .filter { it.startsWith(threadCachePrefix(roomId)) }
                    .forEach { key ->
                        _threadCache.remove(key)
                        activeThreadSubscriptions.remove(key)
                    }
            }
            WatchSync.Invalidation.InvalidationScope.ALL -> {
                _favorites.value = emptyList()
                _companionSettings.value = WatchCompanionSettings()
                _avatarImages.value = emptyMap()
                clearAllMediaPreviewState()
                _summaryCache.clear()
                _timelineCache.clear()
                _threadCache.clear()
                activeRoomSubscriptions.clear()
                activeThreadSubscriptions.clear()
                requestTileRefresh()
            }
        }
    }

    private fun handleFullRefresh() {
        applyInvalidation(WatchSync.Invalidation(WatchSync.Invalidation.InvalidationScope.ALL))
        scheduleCachePersist(
            WatchSyncEnvelope(
                generatedAtMs = System.currentTimeMillis(),
                payload = WatchSync.FullRefresh,
            ),
        )
    }

    private fun requestTileRefresh() {
        pendingTileRefreshJob?.cancel()
        pendingTileRefreshJob = scope.launch {
            delay(TILE_REFRESH_DEBOUNCE_MS)
            runCatching {
                TileService.getUpdater(context).requestUpdate(RecentContactsTileService::class.java)
                TileService.getUpdater(context).requestUpdate(FavoriteContactsTileService::class.java)
            }.onFailure { Timber.w(it, "tile refresh request failed") }
        }
    }

    private fun decode(bytes: ByteArray): WatchSyncEnvelope? = runCatching {
        WatchBridgeSerialization.decodeEnvelopeFromBytes(bytes)
    }.onFailure { Timber.w(it, "envelope decode failed") }.getOrNull()

    private fun removeCachedMediaPreviews(roomId: String, eventIds: List<String>) {
        if (eventIds.isEmpty()) return
        val previewKeys = eventIds.map { eventId -> mediaPreviewCacheKey(roomId, eventId) }
        _mediaPreviewImages.value = _mediaPreviewImages.value.toMutableMap().apply {
            previewKeys.forEach(::remove)
        }
        previewKeys.forEach { key -> mediaPreviewStateFlows[key]?.value = null }
    }

    private fun clearMediaPreviewStateForRoom(roomId: String) {
        val roomKeys = _mediaPreviewImages.value.keys.filter { it.startsWith("$roomId/") }
        _mediaPreviewImages.value = _mediaPreviewImages.value.toMutableMap().apply {
            roomKeys.forEach(::remove)
        }
        roomKeys.forEach { key -> mediaPreviewStateFlows[key]?.value = null }
    }

    private fun clearAllMediaPreviewState() {
        val keys = _mediaPreviewImages.value.keys.toList()
        _mediaPreviewImages.value = emptyMap()
        keys.forEach { key -> mediaPreviewStateFlows[key]?.value = null }
    }

    private fun updateMediaPreviewState(key: String, imageBytes: ByteArray?) {
        _mediaPreviewImages.value = _mediaPreviewImages.value.toMutableMap().apply {
            if (imageBytes == null) remove(key) else put(key, imageBytes)
        }
        mediaPreviewStateFlows.getOrPut(key) { MutableStateFlow(imageBytes) }.value = imageBytes
    }

    private fun scheduleCachePersist(envelope: WatchSyncEnvelope) {
        val key = cachePersistKey(envelope.payload) ?: return
        val debounceMs = cachePersistDebounceMs(envelope.payload)
        if (debounceMs <= 0L) {
            pendingCachePersistJobs.remove(key)?.cancel()
            pendingCachePersists.remove(key)
            scope.launch { persistEnvelope(envelope) }
            return
        }

        pendingCachePersists[key] = envelope
        pendingCachePersistJobs.remove(key)?.cancel()
        lateinit var persistJob: Job
        persistJob = scope.launch {
            delay(debounceMs)
            val latestEnvelope = pendingCachePersists.remove(key) ?: envelope
            persistEnvelope(latestEnvelope)
            if (pendingCachePersistJobs[key] === persistJob) {
                pendingCachePersistJobs.remove(key)
            }
        }
        pendingCachePersistJobs[key] = persistJob
    }

    private suspend fun persistEnvelope(envelope: WatchSyncEnvelope) {
        runCatching { cacheStore.persist(envelope) }
            .onFailure { Timber.w(it, "persist watch cache failed for %s", envelope.payload::class.simpleName) }
    }

    private fun cachePersistKey(payload: WatchPayload): String? = when (payload) {
        is WatchSync.FavoritesSnapshot -> "favorites"
        is WatchSync.SettingsUpdate -> "settings"
        is WatchSync.AvatarUpdate -> "avatar:${payload.roomId}"
        is WatchSync.MediaPreview -> "media:${payload.roomId}/${payload.eventId}"
        is WatchSync.RoomSummary -> "summary:${payload.summary.roomId}"
        is WatchSync.TimelineDelta -> "timeline:${payload.roomId}"
        is WatchSync.ThreadDelta -> "thread:${payload.roomId}/${payload.threadRootEventId}"
        is WatchSync.Invalidation -> "invalidation:${payload.scope}:${payload.roomId.orEmpty()}"
        is WatchSync.FullRefresh -> "full-refresh"
        else -> null
    }

    private fun cachePersistDebounceMs(payload: WatchPayload): Long = when (payload) {
        is WatchSync.Invalidation,
        is WatchSync.FullRefresh -> 0L
        is WatchSync.TimelineDelta,
        is WatchSync.ThreadDelta -> TIMELINE_CACHE_PERSIST_DEBOUNCE_MS
        else -> CACHE_PERSIST_DEBOUNCE_MS
    }

    private fun threadCacheKey(roomId: String, threadRootEventId: String): String = "$roomId/$threadRootEventId"

    private fun threadCachePrefix(roomId: String): String = "$roomId/"

    private fun mergeTimelineItems(
        existing: List<io.element.android.watchbridge.contract.WatchTimelineItem>,
        incoming: List<io.element.android.watchbridge.contract.WatchTimelineItem>,
    ): List<io.element.android.watchbridge.contract.WatchTimelineItem> {
        return (existing + incoming)
            .associateBy { it.eventId }
            .values
            .toList()
    }

    private fun mergeThreadItems(
        existing: List<io.element.android.watchbridge.contract.WatchThreadItem>,
        incoming: List<io.element.android.watchbridge.contract.WatchThreadItem>,
    ): List<io.element.android.watchbridge.contract.WatchThreadItem> {
        return (existing + incoming)
            .associateBy { it.eventId }
            .values
            .toList()
    }

    private data class CachedTimeline(
        val items: List<io.element.android.watchbridge.contract.WatchTimelineItem>,
        val hasSnapshot: Boolean,
    )

    private data class CachedThreadTimeline(
        val items: List<io.element.android.watchbridge.contract.WatchThreadItem>,
        val hasSnapshot: Boolean,
    )
}

class WatchCommandException(
    val code: WatchErrorCode,
    override val message: String? = null,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
