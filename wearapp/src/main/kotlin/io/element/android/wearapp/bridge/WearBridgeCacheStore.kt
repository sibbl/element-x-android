/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.bridge

import android.content.Context
import io.element.android.watchbridge.contract.WatchBridgeSerialization
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
) {
    constructor(context: Context, ioDispatcher: CoroutineDispatcher = Dispatchers.IO) :
        this(File(context.cacheDir, "watchbridge"), ioDispatcher)

    private val mutex = Mutex()

    suspend fun restoreEnvelopes(): List<WatchSyncEnvelope> = withContext(ioDispatcher) {
        mutex.withLock {
            if (!rootDir.exists()) return@withLock emptyList()
            buildList {
                favoritesFile().takeIf(File::exists)?.let { addIfDecoded(it) }
                settingsFile().takeIf(File::exists)?.let { addIfDecoded(it) }
                addDecodedFrom(summaryDir())
                addDecodedFrom(timelineDir())
                addDecodedFrom(threadDir())
                addDecodedFrom(avatarDir())
            }
        }
    }

    suspend fun persist(envelope: WatchSyncEnvelope) = withContext(ioDispatcher) {
        mutex.withLock {
            when (val payload = envelope.payload) {
                is WatchSync.FavoritesSnapshot -> writeEnvelope(favoritesFile(), envelope)
                is WatchSync.SettingsUpdate -> writeEnvelope(settingsFile(), envelope)
                is WatchSync.AvatarUpdate -> writeEnvelope(avatarFile(payload.roomId), envelope)
                is WatchSync.RoomSummary -> writeEnvelope(summaryFile(payload.summary.roomId), envelope)
                is WatchSync.TimelineDelta -> writeEnvelope(timelineFile(payload.roomId), envelope)
                is WatchSync.ThreadDelta -> writeEnvelope(threadFile(payload.roomId, payload.threadRootEventId), envelope)
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

    private fun readAvatarEnvelope(roomId: String): WatchSync.AvatarUpdate? {
        return avatarFile(roomId)
            .takeIf(File::exists)
            ?.decodeEnvelope()
            ?.payload as? WatchSync.AvatarUpdate
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

    private fun favoritesFile(): File = File(rootDir, "favorites.bin")
    private fun settingsFile(): File = File(rootDir, "settings.bin")
    private fun avatarDir(): File = File(rootDir, "avatars")
    private fun summaryDir(): File = File(rootDir, "summaries")
    private fun timelineDir(): File = File(rootDir, "timelines")
    private fun threadDir(): File = File(rootDir, "threads")

    private fun avatarFile(roomId: String): File = File(avatarDir(), "${safeKey(roomId)}.bin")
    private fun summaryFile(roomId: String): File = File(summaryDir(), "${safeKey(roomId)}.bin")
    private fun timelineFile(roomId: String): File = File(timelineDir(), "${safeKey(roomId)}.bin")
    private fun threadFile(roomId: String, threadRootEventId: String): File =
        File(threadDir(), "${safeKey(roomId)}_${safeKey(threadRootEventId)}.bin")

    private fun safeKey(raw: String): String = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.toByteArray(Charsets.UTF_8))
}
