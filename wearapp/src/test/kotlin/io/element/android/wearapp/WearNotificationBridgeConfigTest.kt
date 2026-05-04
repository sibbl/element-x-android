/*
 * Copyright 2026 New Vector Ltd.
 * Wear OS companion fork.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.wearapp

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class WearNotificationBridgeConfigTest {
    @Test
    fun `createWearNotificationBridgingConfig disables phone notification mirroring`() {
        val config = createWearNotificationBridgingConfig(RuntimeEnvironment.getApplication())

        assertThat(config.isBridgingEnabled).isFalse()
        assertThat(config.excludedTags).isEmpty()
    }
}