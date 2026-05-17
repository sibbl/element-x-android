/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.test.notifications.conversations

import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.push.api.notifications.conversations.NotificationConversationService

class FakeNotificationConversationService : NotificationConversationService {
    val onSendMessageCalls = mutableListOf<OnSendMessageCall>()
    val onLeftRoomCalls = mutableListOf<Pair<SessionId, RoomId>>()
    val onAvailableRoomsChangedCalls = mutableListOf<Pair<SessionId, Set<RoomId>>>()

    override suspend fun onSendMessage(
        sessionId: SessionId,
        roomId: RoomId,
        roomName: String?,
        roomIsDirect: Boolean,
        roomAvatarUrl: String?,
    ) {
        onSendMessageCalls += OnSendMessageCall(
            sessionId = sessionId,
            roomId = roomId,
            roomName = roomName,
            roomIsDirect = roomIsDirect,
            roomAvatarUrl = roomAvatarUrl,
        )
    }

    override suspend fun onLeftRoom(sessionId: SessionId, roomId: RoomId) {
        onLeftRoomCalls += sessionId to roomId
    }

    override suspend fun onAvailableRoomsChanged(sessionId: SessionId, roomIds: Set<RoomId>) {
        onAvailableRoomsChangedCalls += sessionId to roomIds
    }

    data class OnSendMessageCall(
        val sessionId: SessionId,
        val roomId: RoomId,
        val roomName: String,
        val roomIsDirect: Boolean,
        val roomAvatarUrl: String?,
    )
}
