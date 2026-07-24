/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

@Composable
internal fun PressableWearChip(
    onTap: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    onSwipeLeft: (() -> Unit)? = null,
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
            .then(
                onSwipeLeft?.let { callback ->
                    Modifier.pointerInput(callback) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var totalX = 0f
                            var totalY = 0f
                            var directionResolved = false
                            var interceptLeftSwipe = false
                            var pointer = down
                            while (pointer.pressed) {
                                // Observe before the navigator. Once the gesture is clearly horizontal
                                // and left-bound, reserve it for message details. Right swipes remain
                                // untouched so Wear swipe-to-dismiss keeps working.
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                                val delta = pointer.position - pointer.previousPosition
                                totalX += delta.x
                                totalY += delta.y
                                if (!directionResolved &&
                                    (abs(totalX) >= viewConfiguration.touchSlop || abs(totalY) >= viewConfiguration.touchSlop)
                                ) {
                                    directionResolved = true
                                    interceptLeftSwipe = totalX < 0f && abs(totalX) > abs(totalY)
                                }
                                if (interceptLeftSwipe) pointer.consume()
                            }
                            if (interceptLeftSwipe && totalX <= SWIPE_LEFT_THRESHOLD_PX) callback()
                        }
                    }
                } ?: Modifier,
            )
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

private const val SWIPE_LEFT_THRESHOLD_PX = -48f
