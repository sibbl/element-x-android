/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class WearMainActivityDeepLinkTest {

    @Test
    fun `consume pending deep link clears extras after first use`() {
        val intent = Intent()
            .putExtra("roomId", "!room:server")
            .putExtra("eventId", "\$event:server")

        assertThat(consumePendingDeepLink(intent)).isEqualTo("!room:server" to "\$event:server")
        assertThat(intent.hasExtra("roomId")).isFalse()
        assertThat(intent.hasExtra("eventId")).isFalse()
        assertThat(consumePendingDeepLink(intent)).isNull()
    }
}
