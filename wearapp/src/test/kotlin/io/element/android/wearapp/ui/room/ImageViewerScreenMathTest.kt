/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui.room

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ImageViewerScreenMathTest {

    @Test
    fun `clamp zoom offset resets translation when image is not zoomed`() {
        val clamped = clampZoomOffset(
            offset = Offset(64f, 32f),
            containerSize = Size(200f, 120f),
            scale = 1f,
        )

        assertThat(clamped).isEqualTo(Offset.Zero)
    }

    @Test
    fun `double tap zoom offset centers the tapped area within bounds`() {
        val offset = doubleTapZoomOffset(
            containerSize = Size(200f, 100f),
            tapOffset = Offset.Zero,
            scale = 2.5f,
        )

        assertThat(offset.x).isEqualTo(150f)
        assertThat(offset.y).isEqualTo(75f)
    }

    @Test
    fun `adjusted pan delta keeps zoomed image movement responsive`() {
        val delta = adjustedPanDelta(
            dragAmount = Offset(10f, -6f),
            scale = 2.5f,
        )

        assertThat(delta.x).isEqualTo(20f)
        assertThat(delta.y).isEqualTo(-12f)
        assertThat(zoomedPanMultiplier(2.5f)).isEqualTo(2f)
    }
}