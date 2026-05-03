/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.notifications

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Multibinds
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.push.impl.notifications.model.NotifiableMessageEvent
import io.element.android.libraries.push.impl.notifications.model.ResolvedPushEvent

/**
 * Optional hooks that let companion-specific runtimes mirror or transform phone notifications.
 *
 * The default graph provides an empty set so non-companion variants stay unaffected.
 */
interface CompanionNotificationBridge {
    suspend fun onMessageNotificationsRendered(events: List<NotifiableMessageEvent>) = Unit

    suspend fun onMessagesClearedForRoom(sessionId: SessionId, roomId: RoomId) = Unit

    suspend fun onMessagesClearedForThread(sessionId: SessionId, roomId: RoomId, threadId: ThreadId) = Unit

    suspend fun onAllMessagesCleared(sessionId: SessionId) = Unit

    suspend fun onSessionCleared(sessionId: SessionId) = Unit

    suspend fun onMessageNotificationsRedacted(redactions: List<ResolvedPushEvent.Redaction>) = Unit
}

@BindingContainer
@ContributesTo(AppScope::class)
interface CompanionNotificationBridgeModule {
    @Multibinds
    fun companionNotificationBridges(): Set<@JvmSuppressWildcards CompanionNotificationBridge>
}