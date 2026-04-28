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
    const val ROOM_SUMMARY: String = "${WatchProtocol.DATA_PATH_PREFIX}/room/summary"
    const val ROOM_TIMELINE: String = "${WatchProtocol.DATA_PATH_PREFIX}/room/timeline"
    const val THREAD: String = "${WatchProtocol.DATA_PATH_PREFIX}/thread"
    const val COMMAND: String = "${WatchProtocol.DATA_PATH_PREFIX}/command"
    const val ACK: String = "${WatchProtocol.DATA_PATH_PREFIX}/ack"
    const val VOICE_DRAFT_CHANNEL: String = "${WatchProtocol.DATA_PATH_PREFIX}/voice/draft"
    const val VOICE_PLAYBACK_CHANNEL: String = "${WatchProtocol.DATA_PATH_PREFIX}/voice/playback"
    const val SETTINGS: String = "${WatchProtocol.DATA_PATH_PREFIX}/settings"

    fun avatar(roomId: String): String = "$AVATAR/${roomId.toDataPathSegment()}"
    fun roomTimeline(roomId: String): String = "$ROOM_TIMELINE/$roomId"
    fun thread(roomId: String, threadRootEventId: String): String = "$THREAD/$roomId/$threadRootEventId"
    fun voiceDraftChannel(draftId: String): String = "$VOICE_DRAFT_CHANNEL/$draftId"
    fun voiceDraftId(path: String): String? = path
        .takeIf { it.startsWith("$VOICE_DRAFT_CHANNEL/") }
        ?.removePrefix("$VOICE_DRAFT_CHANNEL/")
        ?.takeIf { it.isNotBlank() }

    private fun String.toDataPathSegment(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(toByteArray(Charsets.UTF_8))
}
