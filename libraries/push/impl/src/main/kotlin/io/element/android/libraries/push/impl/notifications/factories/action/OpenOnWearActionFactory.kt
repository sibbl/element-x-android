/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.notifications.factories.action

import androidx.core.app.NotificationCompat
import dev.zacsweers.metro.Inject
import io.element.android.libraries.designsystem.icons.CompoundDrawables
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.push.impl.R
import io.element.android.libraries.push.impl.notifications.factories.PendingIntentFactory
import io.element.android.services.toolbox.api.strings.StringProvider

@Inject
class OpenOnWearActionFactory(
    private val pendingIntentFactory: PendingIntentFactory,
    private val stringProvider: StringProvider,
) {
    fun create(
        roomId: RoomId,
        eventId: EventId?,
        threadId: ThreadId?,
    ): NotificationCompat.Action {
        return NotificationCompat.Action.Builder(
            CompoundDrawables.ic_compound_devices,
            stringProvider.getString(R.string.notification_room_action_open_on_watch),
            pendingIntentFactory.createOpenOnWearPendingIntent(roomId = roomId, eventId = eventId, threadId = threadId),
        ).build()
    }
}