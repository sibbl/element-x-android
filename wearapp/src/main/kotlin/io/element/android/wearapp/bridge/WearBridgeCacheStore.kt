/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.bridge

import android.content.Context
import io.element.android.watchbridge.contract.WatchBridgeSerialization
import io.element.android.watchbridge.contract.WatchCompanionSettings
import io.element.android.watchbridge.contract.WatchFavoriteRoom
import io.element.android.watchbridge.contract.WatchSync
import io.element.android.watchbridge.contract.WatchSyncEnvelope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.Base64

/**
 * Small file-backed cache stored in the Wear app cache directory.
 *
 * We keep the latest favorites snapshot, room summaries, timelines, thread timelines and avatars so
 * the watch can render immediately after process death / reboot, even before the phone reconnects.
 */
internal class WearBridgeCacheStore(
    private val rootDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxMediaCacheBytes: Long = DEFAULT_MAX_MEDIA_CACHE_BYTES,
) {
    constructor(context: Context, ioDispatcher: CoroutineDispatcher = Dispatchers.IO) :
        this(File(context.cacheDir, "watchbridge"), ioDispatcher)

    private val mutex = Mutex()

    suspend fun restoreEnvelopes(): List<WatchSyncEnvelope> = withContext(ioDispatcher) {
        mutex.withLock {
            if (!rootDir.exists()) return@withLock emptyList()
            pruneExpiredMediaLocked()
            buildList {
                favoritesFile().takeIf(File::exists)?.let { addIfDecoded(it) }
                settingsFile().takeIf(File::exists)?.let { addIfDecoded(it) }
                addDecodedFrom(summaryDir())
                addDecodedFrom(timelineDir())
                addDecodedFrom(threadDir())
                addDecodedFrom(avatarDir())
                addDecodedFrom(mediaDir())
            }
        }
    }

    suspend fun restoreNotificationEnvelopes(roomId: String? = null): List<WatchSyncEnvelope> = withContext(ioDispatcher) {
        mutex.withLock {
            if (!rootDir.exists()) return@withLock emptyList()
            buildList {
                favoritesFile().takeIf(File::exists)?.let { addIfDecoded(it) }
                settingsFile().takeIf(File::exists)?.let { addIfDecoded(it) }
                roomId?.let { avatarFile(it).takeIf(File::exists)?.let { file -> addIfDecoded(file) } }
            }
        }
    }

    suspend fun persist(envelope: WatchSyncEnvelope) = withContext(ioDispatcher) {
        mutex.withLock {
            when (val payload = envelope.payload) {
                is WatchSync.FavoritesSnapshot -> writeEnvelope(favoritesFile(), envelope)
                is WatchSync.SettingsUpdate -> writeEnvelope(settingsFile(), envelope)
                is WatchSync.AvatarUpdate -> writeEnvelope(avatarFile(payload.roomId), envelope)
                is WatchSync.MediaPreview -> {
                    if (payload.imageBytes == null) {
                        mediaFile(payload.roomId, payload.eventId).delete()
                    } else {
                        writeMediaEnvelope(mediaFile(payload.roomId, payload.eventId), envelope)
                    }
                }
                is WatchSync.RoomSummary -> writeEnvelope(summaryFile(payload.summary.roomId), envelope)
                is WatchSync.TimelineDelta -> {
                    payload.removedEventIds.forEach { eventId -> mediaFile(payload.roomId, eventId).delete() }
                    writeEnvelope(timelineFile(payload.roomId), envelope)
                }
                is WatchSync.ThreadDelta -> {
                    payload.removedEventIds.forEach { eventId -> mediaFile(payload.roomId, eventId).delete() }
                    writeEnvelope(threadFile(payload.roomId, payload.threadRootEventId), envelope)
                }
                is WatchSync.Invalidation -> handleInvalidation(payload)
                is WatchSync.FullRefresh -> clearAll()
                is WatchSync.UnreadUpdate -> Unit
                else -> Unit
            }
        }
    }

    suspend fun readFavorites(): List<WatchFavoriteRoom> = withContext(ioDispatcher) {
        mutex.withLock {
            val payload = favoritesFile()
                .takeIf(File::exists)
                ?.decodeEnvelope()
                ?.payload as? WatchSync.FavoritesSnapshot
            payload?.rooms.orEmpty()
        }
    }

    suspend fun readSettings(): WatchCompanionSettings = withContext(ioDispatcher) {
        mutex.withLock {
            val payload = settingsFile()
                .takeIf(File::exists)
                ?.decodeEnvelope()
                ?.payload as? WatchSync.SettingsUpdate
            payload?.settings ?: WatchCompanionSettings()
        }
    }

    suspend fun readAvatar(roomId: String): ByteArray? = withContext(ioDispatcher) {
        mutex.withLock {
            val payload = avatarFile(roomId)
                .takeIf(File::exists)
                ?.decodeEnvelope()
                ?.payload as? WatchSync.AvatarUpdate
            payload?.imageBytes
        }
    }

    suspend fun readAvatars(roomIds: Iterable<String>): Map<String, ByteArray> = withContext(ioDispatcher) {
        mutex.withLock {
            buildMap {
                roomIds.forEach { roomId ->
                    avatarFile(roomId)
                        .takeIf(File::exists)
                        ?.decodeEnvelope()
                        ?.payload
                        ?.let { it as? WatchSync.AvatarUpdate }
                        ?.imageBytes
                        ?.let { put(roomId, it) }
                }
            }
        }
    }

    suspend fun readLatestTimelineEventIds(roomIds: Iterable<String>): Map<String, String> = withContext(ioDispatcher) {
        mutex.withLock {
            buildMap {
                roomIds.forEach { roomId ->
                    val latestEventId = timelineFile(roomId)
                        .takeIf(File::exists)
                        ?.decodeEnvelope()
                        ?.payload
                        ?.let { it as? WatchSync.TimelineDelta }
                        ?.items
                        ?.lastOrNull()
                        ?.eventId
                    if (latestEventId != null) {
                        put(roomId, latestEventId)
                    }
                }
            }
        }
    }

    suspend fun readMediaPreview(roomId: String, eventId: String): ByteArray? = withContext(ioDispatcher) {
        mutex.withLock {
            readMediaEnvelope(roomId, eventId)?.imageBytes
        }
    }

    private fun readAvatarEnvelope(roomId: String): WatchSync.AvatarUpdate? {
        return avatarFile(roomId)
            .takeIf(File::exists)
            ?.decodeEnvelope()
            ?.payload as? WatchSync.AvatarUpdate
    }

    private fun readMediaEnvelope(roomId: String, eventId: String): WatchSync.MediaPreview? {
        val file = mediaFile(roomId, eventId)
        if (!file.exists()) return null
        val envelope = file.decodeEnvelope() ?: return null
        if (envelope.expiresAtMs?.let { it <= clock() } == true) {
            file.delete()
            return null
        }
        file.setLastModified(clock())
        return envelope.payload as? WatchSync.MediaPreview
    }

    private fun MutableList<WatchSyncEnvelope>.addDecodedFrom(directory: File) {
        if (!directory.exists()) return
        directory.listFiles()
            .orEmpty()
            .sortedBy { it.name }
            .forEach { file ->
                file.decodeEnvelope()?.let(::add)
            }
    }

    private fun MutableList<WatchSyncEnvelope>.addIfDecoded(file: File) {
        file.decodeEnvelope()?.let(::add)
    }

    private fun handleInvalidation(payload: WatchSync.Invalidation) {
        when (payload.scope) {
            WatchSync.Invalidation.InvalidationScope.FAVORITES -> favoritesFile().delete()
            WatchSync.Invalidation.InvalidationScope.ROOM -> payload.roomId?.let { roomId ->
                summaryFile(roomId).delete()
                timelineFile(roomId).delete()
                avatarFile(roomId).delete()
                mediaDir().listFiles().orEmpty()
                    .filter { it.name.startsWith("${safeKey(roomId)}_") }
                    .forEach(File::delete)
                threadDir().listFiles().orEmpty()
                    .filter { it.name.startsWith("${safeKey(roomId)}_") }
                    .forEach(File::delete)
            }
            WatchSync.Invalidation.InvalidationScope.THREAD -> payload.roomId?.let { roomId ->
                threadDir().listFiles().orEmpty()
                    .filter { it.name.startsWith("${safeKey(roomId)}_") }
                    .forEach(File::delete)
            }
            WatchSync.Invalidation.InvalidationScope.ALL -> clearAll()
        }
    }

    private fun clearAll() {
        rootDir.deleteRecursively()
    }

    private fun writeMediaEnvelope(target: File, envelope: WatchSyncEnvelope) {
        pruneExpiredMediaLocked()
        val bytes = WatchBridgeSerialization.encodeEnvelopeToBytes(envelope)
        if (!ensureMediaCapacityLocked(target = target, incomingSize = bytes.size.toLong())) {
            Timber.w("watch cache media write skipped because quota is full target=%s", target.absolutePath)
            return
        }
        writeEnvelope(target, envelope)
    }

    private fun writeEnvelope(target: File, envelope: WatchSyncEnvelope) {
        val bytes = WatchBridgeSerialization.encodeEnvelopeToBytes(envelope)
        target.parentFile?.mkdirs()
        val tmpFile = File(target.parentFile, "${target.name}.tmp")
        tmpFile.writeBytes(bytes)
        if (!tmpFile.renameTo(target)) {
            target.writeBytes(bytes)
            tmpFile.delete()
        }
    }

    private fun File.decodeEnvelope(): WatchSyncEnvelope? = runCatching {
        WatchBridgeSerialization.decodeEnvelopeFromBytes(readBytes())
    }.onFailure {
        Timber.w(it, "watch cache decode failed for %s", absolutePath)
        delete()
    }.getOrNull()

    private fun pruneExpiredMediaLocked() {
        val now = clock()
        mediaDir().listFiles().orEmpty().forEach { file ->
            val envelope = file.decodeEnvelope() ?: return@forEach
            if (envelope.expiresAtMs?.let { it <= now } == true) {
                file.delete()
            }
        }
    }

    private fun ensureMediaCapacityLocked(target: File, incomingSize: Long): Boolean {
        if (incomingSize > maxMediaCacheBytes) return false
        val files = mediaDir().listFiles().orEmpty().toMutableList()
        var sizeAfterEviction = files.sumOf { file -> if (file == target) 0L else file.length() }
        if (sizeAfterEviction + incomingSize <= maxMediaCacheBytes) return true

        files.sortBy { it.lastModified() }
        files.forEach { file ->
            if (file == target || sizeAfterEviction + incomingSize <= maxMediaCacheBytes) return@forEach
            sizeAfterEviction -= file.length()
            file.delete()
        }
        return sizeAfterEviction + incomingSize <= maxMediaCacheBytes
    }

    private fun favoritesFile(): File = File(rootDir, "favorites.bin")
    private fun settingsFile(): File = File(rootDir, "settings.bin")
    private fun avatarDir(): File = File(rootDir, "avatars")
    private fun mediaDir(): File = File(rootDir, "media")
    private fun summaryDir(): File = File(rootDir, "summaries")
    private fun timelineDir(): File = File(rootDir, "timelines")
    private fun threadDir(): File = File(rootDir, "threads")

    private fun avatarFile(roomId: String): File = File(avatarDir(), "${safeKey(roomId)}.bin")
    private fun mediaFile(roomId: String, eventId: String): File =
        File(mediaDir(), "${safeKey(roomId)}_${safeKey(eventId)}.bin")
    private fun summaryFile(roomId: String): File = File(summaryDir(), "${safeKey(roomId)}.bin")
    private fun timelineFile(roomId: String): File = File(timelineDir(), "${safeKey(roomId)}.bin")
    private fun threadFile(roomId: String, threadRootEventId: String): File =
        File(threadDir(), "${safeKey(roomId)}_${safeKey(threadRootEventId)}.bin")

    private fun safeKey(raw: String): String = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.toByteArray(Charsets.UTF_8))

    companion object {
        private const val DEFAULT_MAX_MEDIA_CACHE_BYTES: Long = 8L * 1024L * 1024L
    }
}
