/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.watchbridge.contract

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WatchProtocolTest {
    @Test
    fun `variant capabilities include the application id`() {
        assertThat(WatchProtocol.phoneCapability("io.element.android.x"))
            .isEqualTo("element_x_watchbridge_phone_io_element_android_x")
        assertThat(WatchProtocol.watchCapability("io.element.android.x.debug"))
            .isEqualTo("element_x_watchbridge_watch_io_element_android_x_debug")
    }

    @Test
    fun `envelopes match only the same application id once stamped`() {
        val envelope = WatchSyncEnvelope(
            generatedAtMs = 1L,
            payload = WatchSync.FullRefresh,
        )

        val stamped = envelope.stampedForApplicationId("io.element.android.x")

        assertThat(envelope.isForApplicationId("io.element.android.x.debug")).isTrue()
        assertThat(stamped.isForApplicationId("io.element.android.x")).isTrue()
        assertThat(stamped.isForApplicationId("io.element.android.x.debug")).isFalse()
    }

    @Test
    fun `voice draft channel paths are scoped to the application id`() {
        val releasePath = WatchDataPaths.voiceDraftChannel(
            applicationId = "io.element.android.x",
            draftId = "draft-1",
        )

        assertThat(WatchDataPaths.voiceDraftId(releasePath, "io.element.android.x")).isEqualTo("draft-1")
        assertThat(WatchDataPaths.voiceDraftId(releasePath, "io.element.android.x.debug")).isNull()
        assertThat(WatchDataPaths.voiceDraftId(releasePath)).isNull()
    }
}
