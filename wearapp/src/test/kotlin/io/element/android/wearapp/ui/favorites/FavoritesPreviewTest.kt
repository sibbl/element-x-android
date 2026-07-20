/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui.favorites

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FavoritesPreviewTest {
    @Test
    fun `conversation preview removes sending prefix`() {
        assertThat(cleanConversationPreview(" Sending: Hello ")).isEqualTo("Hello")
        assertThat(cleanConversationPreview("Sending… Voice message")).isEqualTo("Voice message")
    }

    @Test
    fun `conversation preview removes markdown formatting`() {
        assertThat(cleanConversationPreview("**Important** [details](https://example.org)"))
            .isEqualTo("Important details")
        assertThat(cleanConversationPreview("@first_last:example.org"))
            .isEqualTo("@first_last:example.org")
    }

    @Test
    fun `empty conversation preview stays hidden`() {
        assertThat(cleanConversationPreview(null)).isNull()
        assertThat(cleanConversationPreview("Sending: ")).isNull()
    }
}
