/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui.common

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics

internal fun Modifier.wearTapAndLongPress(
    onTap: () -> Unit,
    onLongPress: (() -> Unit)? = null,
): Modifier = this
    .pointerInput(onTap, onLongPress) {
        detectTapGestures(
            onTap = { onTap() },
            onLongPress = { onLongPress?.invoke() },
        )
    }
    .semantics(mergeDescendants = true) {
        role = Role.Button
        onClick(action = {
            onTap()
            true
        })
        onLongPress?.let { callback ->
            onLongClick(action = {
                callback()
                true
            })
        }
    }