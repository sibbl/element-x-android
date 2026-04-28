/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.watchbridge.contract

/** Data Layer paths used by the companion protocol. */
object WatchDataPaths {
    const val FAVORITES: String = "${WatchProtocol.DATA_PATH_PREFIX}/favorites"
    const val ROOM_SUMMARY: String = "${WatchProtocol.DATA_PATH_PREFIX}/room/summary"
    const val ROOM_TIMELINE: String = "${WatchProtocol.DATA_PATH_PREFIX}/room/timeline"
    const val THREAD: String = "${WatchProtocol.DATA_PATH_PREFIX}/thread"
    const val COMMAND: String = "${WatchProtocol.DATA_PATH_PREFIX}/command"
    const val ACK: String = "${WatchProtocol.DATA_PATH_PREFIX}/ack"
    const val VOICE_DRAFT_CHANNEL: String = "${WatchProtocol.DATA_PATH_PREFIX}/voice/draft"
    const val VOICE_PLAYBACK_CHANNEL: String = "${WatchProtocol.DATA_PATH_PREFIX}/voice/playback"
    const val SETTINGS: String = "${WatchProtocol.DATA_PATH_PREFIX}/settings"

    fun roomTimeline(roomId: String): String = "$ROOM_TIMELINE/$roomId"
    fun thread(roomId: String, threadRootEventId: String): String = "$THREAD/$roomId/$threadRootEventId"
}
