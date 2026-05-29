/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.watchbridge.contract

import java.util.Base64

/** Data Layer paths used by the companion protocol. */
object WatchDataPaths {
    const val FAVORITES: String = "${WatchProtocol.DATA_PATH_PREFIX}/favorites"
    private const val AVATAR: String = "${WatchProtocol.DATA_PATH_PREFIX}/avatar"
    private const val MEDIA_PREVIEW: String = "${WatchProtocol.DATA_PATH_PREFIX}/media"
    private const val NOTIFICATION: String = "${WatchProtocol.DATA_PATH_PREFIX}/notification"
    const val ROOM_SUMMARY: String = "${WatchProtocol.DATA_PATH_PREFIX}/room/summary"
    const val ROOM_TIMELINE: String = "${WatchProtocol.DATA_PATH_PREFIX}/room/timeline"
    const val THREAD: String = "${WatchProtocol.DATA_PATH_PREFIX}/thread"
    const val COMMAND: String = "${WatchProtocol.DATA_PATH_PREFIX}/command"
    const val ACK: String = "${WatchProtocol.DATA_PATH_PREFIX}/ack"
    const val VOICE_DRAFT_CHANNEL: String = "${WatchProtocol.DATA_PATH_PREFIX}/voice/draft"
    const val VOICE_PLAYBACK_CHANNEL: String = "${WatchProtocol.DATA_PATH_PREFIX}/voice/playback"
    const val SETTINGS: String = "${WatchProtocol.DATA_PATH_PREFIX}/settings"
    const val FULL_REFRESH: String = "${WatchProtocol.DATA_PATH_PREFIX}/full-refresh"

    fun avatar(roomId: String): String = "$AVATAR/${roomId.toDataPathSegment()}"
    fun mediaPreview(roomId: String, eventId: String): String =
        "$MEDIA_PREVIEW/${roomId.toDataPathSegment()}/${eventId.toDataPathSegment()}"
    fun notification(notificationKey: String): String = "$NOTIFICATION/${notificationKey.toDataPathSegment()}"
    fun notificationKey(path: String): String? = path
        .takeIf { it.startsWith("$NOTIFICATION/") }
        ?.removePrefix("$NOTIFICATION/")
        ?.takeIf { it.isNotBlank() }
        ?.fromDataPathSegment()
    fun roomTimeline(roomId: String): String = "$ROOM_TIMELINE/$roomId"
    fun thread(roomId: String, threadRootEventId: String): String = "$THREAD/$roomId/$threadRootEventId"
    fun voiceDraftChannel(draftId: String): String = "$VOICE_DRAFT_CHANNEL/$draftId"
    fun voiceDraftChannel(applicationId: String, draftId: String): String =
        "$VOICE_DRAFT_CHANNEL/${applicationId.toDataPathSegment()}/${draftId.toDataPathSegment()}"
    fun voiceDraftId(path: String, applicationId: String): String? {
        val prefix = "$VOICE_DRAFT_CHANNEL/${applicationId.toDataPathSegment()}/"
        return path
            .takeIf { it.startsWith(prefix) }
            ?.removePrefix(prefix)
            ?.takeIf { it.isNotBlank() }
            ?.fromDataPathSegment()
    }
    fun voiceDraftId(path: String): String? = path
        .takeIf { it.startsWith("$VOICE_DRAFT_CHANNEL/") }
        ?.removePrefix("$VOICE_DRAFT_CHANNEL/")
        ?.takeIf { it.isNotBlank() && '/' !in it }

    private fun String.toDataPathSegment(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(toByteArray(Charsets.UTF_8))

    private fun String.fromDataPathSegment(): String? = runCatching {
        String(Base64.getUrlDecoder().decode(this), Charsets.UTF_8)
    }.getOrNull()
}
