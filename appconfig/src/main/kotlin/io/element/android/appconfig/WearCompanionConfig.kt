/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.appconfig

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

data class WearCompanionDeepLink(
    val roomId: String,
    val eventId: String? = null,
    val threadRootEventId: String? = null,
)

object WearCompanionConfig {
    const val ACTION_OPEN_ON_WEAR = "io.element.android.action.OPEN_ON_WEAR"
    const val SETTINGS_ACTIVITY_CLASS_NAME = "io.element.android.x.watchbridge.WearCompanionSettingsActivity"
    const val OPEN_ON_WEAR_ACTIVITY_CLASS_NAME = "io.element.android.x.watchbridge.OpenOnWearActivity"

    const val EXTRA_ROOM_ID = "io.element.android.wear.roomId"
    const val EXTRA_EVENT_ID = "io.element.android.wear.eventId"
    const val EXTRA_THREAD_ROOT_EVENT_ID = "io.element.android.wear.threadRootEventId"

    const val DEEP_LINK_SCHEME = "elementxwear"
    const val DEEP_LINK_HOST = "open"
}

fun Context.createWearCompanionSettingsIntent(): Intent =
    Intent().setClassName(packageName, WearCompanionConfig.SETTINGS_ACTIVITY_CLASS_NAME)

fun Context.createOpenOnWearActivityIntent(): Intent =
    Intent(WearCompanionConfig.ACTION_OPEN_ON_WEAR)
        .setClassName(packageName, WearCompanionConfig.OPEN_ON_WEAR_ACTIVITY_CLASS_NAME)

fun Context.hasWearCompanionSettingsActivity(): Boolean =
    hasActivity(WearCompanionConfig.SETTINGS_ACTIVITY_CLASS_NAME)

fun Context.hasOpenOnWearActivity(): Boolean =
    hasActivity(WearCompanionConfig.OPEN_ON_WEAR_ACTIVITY_CLASS_NAME)

private fun Context.hasActivity(className: String): Boolean {
    val componentName = ComponentName(packageName, className)
    return runCatching {
        // The legacy overload remains supported and is also implemented consistently by
        // Robolectric. The flags-based overload can incorrectly report dynamically
        // registered activities as missing in unit tests.
        @Suppress("DEPRECATION")
        packageManager.getActivityInfo(componentName, 0)
    }.isSuccess
}

fun buildWearCompanionDeepLink(
    roomId: String,
    eventId: String? = null,
    threadRootEventId: String? = null,
): Uri {
    require(roomId.isNotBlank()) { "roomId cannot be blank" }

    return Uri.Builder()
        .scheme(WearCompanionConfig.DEEP_LINK_SCHEME)
        .authority(WearCompanionConfig.DEEP_LINK_HOST)
        .apply {
            when {
                eventId != null && threadRootEventId != null -> {
                    appendPath("thread-message")
                    appendPath(roomId)
                    appendPath(threadRootEventId)
                    appendPath(eventId)
                }
                threadRootEventId != null -> {
                    appendPath("thread")
                    appendPath(roomId)
                    appendPath(threadRootEventId)
                }
                eventId != null -> {
                    appendPath("message")
                    appendPath(roomId)
                    appendPath(eventId)
                }
                else -> {
                    appendPath("room")
                    appendPath(roomId)
                }
            }
        }
        .build()
}

fun parseWearCompanionDeepLink(uri: Uri?): WearCompanionDeepLink? {
    if (uri == null || uri.scheme != WearCompanionConfig.DEEP_LINK_SCHEME || uri.host != WearCompanionConfig.DEEP_LINK_HOST) {
        return null
    }

    val segments = uri.pathSegments
    return when (segments.firstOrNull()) {
        "room" -> {
            val roomId = segments.getOrNull(1)?.takeIf { it.isNotBlank() }?.let(Uri::decode)
            roomId?.let { WearCompanionDeepLink(roomId = it) }
        }
        "message" -> {
            val roomId = segments.getOrNull(1)?.takeIf { it.isNotBlank() }?.let(Uri::decode)
            val eventId = segments.getOrNull(2)?.takeIf { it.isNotBlank() }?.let(Uri::decode)
            if (roomId != null && eventId != null) {
                WearCompanionDeepLink(roomId = roomId, eventId = eventId)
            } else {
                null
            }
        }
        "thread-message" -> {
            val roomId = segments.getOrNull(1)?.takeIf { it.isNotBlank() }?.let(Uri::decode)
            val threadRootEventId = segments.getOrNull(2)?.takeIf { it.isNotBlank() }?.let(Uri::decode)
            val eventId = segments.getOrNull(3)?.takeIf { it.isNotBlank() }?.let(Uri::decode)
            if (roomId != null && threadRootEventId != null && eventId != null) {
                WearCompanionDeepLink(roomId = roomId, eventId = eventId, threadRootEventId = threadRootEventId)
            } else {
                null
            }
        }
        "thread" -> {
            val roomId = segments.getOrNull(1)?.takeIf { it.isNotBlank() }?.let(Uri::decode)
            val threadRootEventId = segments.getOrNull(2)?.takeIf { it.isNotBlank() }?.let(Uri::decode)
            if (roomId != null && threadRootEventId != null) {
                WearCompanionDeepLink(roomId = roomId, threadRootEventId = threadRootEventId)
            } else {
                null
            }
        }
        else -> null
    }
}
