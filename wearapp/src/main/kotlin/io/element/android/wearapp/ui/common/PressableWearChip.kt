/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun PressableWearChip(
    onTap: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    label: @Composable () -> Unit,
    secondaryLabel: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(backgroundColor)
            .wearTapAndLongPress(
                onTap = onTap,
                onLongPress = onLongPress,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            icon?.let { iconContent ->
                Box(
                    modifier = Modifier.size(28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    iconContent()
                }
            }

            Column(
                modifier = Modifier,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                label()
                secondaryLabel?.let { secondary ->
                    secondary()
                }
            }
        }
    }
}