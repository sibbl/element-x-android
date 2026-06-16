/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.home.impl.datasource

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.dateformatter.api.DateFormatter
import io.element.android.libraries.dateformatter.test.FakeDateFormatter
import io.element.android.libraries.eventformatter.api.RoomLatestEventFormatter
import io.element.android.libraries.eventformatter.test.FakeRoomLatestEventFormatter
import io.element.android.libraries.matrix.api.user.MatrixUser
import io.element.android.libraries.matrix.test.AN_AVATAR_URL
import io.element.android.libraries.matrix.test.A_USER_ID
import io.element.android.libraries.matrix.test.A_USER_NAME
import io.element.android.libraries.matrix.test.room.aRoomInfo
import io.element.android.libraries.matrix.test.room.aRoomSummary
import org.junit.Test

fun aRoomListRoomSummaryFactory(
    dateFormatter: DateFormatter = FakeDateFormatter { _, _, _ -> "Today" },
    roomLatestEventFormatter: RoomLatestEventFormatter = FakeRoomLatestEventFormatter(),
) = RoomListRoomSummaryFactory(
    dateFormatter = dateFormatter,
    roomLatestEventFormatter = roomLatestEventFormatter,
)

class RoomListRoomSummaryFactoryTest {
    @Test
    fun `create keeps official room avatar projection when DM room avatar is missing`() {
        val hero = MatrixUser(
            userId = A_USER_ID,
            displayName = A_USER_NAME,
            avatarUrl = AN_AVATAR_URL,
        )
        val roomSummary = aRoomSummary(
            info = aRoomInfo(
                name = null,
                avatarUrl = null,
                isDm = true,
                heroes = listOf(hero),
            ),
        )

        val result = aRoomListRoomSummaryFactory().create(roomSummary)

        assertThat(result.avatarData.url).isNull()
        assertThat(result.heroes.single().id).isEqualTo(A_USER_ID.value)
        assertThat(result.heroes.single().name).isEqualTo(A_USER_NAME)
        assertThat(result.heroes.single().url).isEqualTo(AN_AVATAR_URL)
    }

    @Test
    fun `create keeps explicit room avatar ahead of DM hero avatar`() {
        val roomAvatarUrl = "mxc://room/avatar"
        val hero = MatrixUser(
            userId = A_USER_ID,
            displayName = A_USER_NAME,
            avatarUrl = AN_AVATAR_URL,
        )
        val roomSummary = aRoomSummary(
            info = aRoomInfo(
                avatarUrl = roomAvatarUrl,
                isDm = true,
                heroes = listOf(hero),
            ),
        )

        val result = aRoomListRoomSummaryFactory().create(roomSummary)

        assertThat(result.avatarData.url).isEqualTo(roomAvatarUrl)
    }

}
