/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui.room

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import io.element.android.wearapp.R
import io.element.android.wearapp.bridge.WearBridgeClient
import io.element.android.wearapp.bridge.mediaPreviewCacheKey
import kotlinx.coroutines.delay

private const val DOUBLE_TAP_ZOOM_SCALE = 2.5f
private const val MAX_ZOOMED_PAN_MULTIPLIER = 2f

@Composable
fun ImageViewerScreen(
    bridge: WearBridgeClient,
    roomId: String,
    eventId: String,
) {
    val mediaPreviewImages by bridge.mediaPreviewImages.collectAsState()
    val imageBytes = mediaPreviewImages[mediaPreviewCacheKey(roomId, eventId)]
    val imageBitmap = rememberDecodedImageBitmap(imageBytes)
    var shouldShowUnavailable by remember(roomId, eventId, imageBytes) { mutableStateOf(false) }

    LaunchedEffect(roomId, eventId, imageBytes) {
        if (imageBytes != null) {
            shouldShowUnavailable = false
        } else {
            shouldShowUnavailable = false
            delay(1_500L)
            shouldShowUnavailable = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background),
        contentAlignment = Alignment.Center,
    ) {
        if (imageBitmap != null) {
            ZoomableImage(bitmap = imageBitmap)
        } else if (!shouldShowUnavailable) {
            CircularProgressIndicator()
        } else {
            Text(
                text = stringResource(R.string.screen_media_viewer_unavailable),
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
}

@Composable
private fun ZoomableImage(bitmap: androidx.compose.ui.graphics.ImageBitmap) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val backGestureEdgePx = with(density) { 24.dp.toPx() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds(),
    ) {
        val containerSize = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }

        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
                .pointerInput(scale, containerSize, backGestureEdgePx) {
                    var shouldHandleDrag = false
                    detectDragGestures(
                        onDragStart = { startOffset ->
                            shouldHandleDrag = scale > 1f && startOffset.x > backGestureEdgePx
                        },
                        onDragEnd = { shouldHandleDrag = false },
                        onDragCancel = { shouldHandleDrag = false },
                        onDrag = { change, dragAmount ->
                            if (!shouldHandleDrag) return@detectDragGestures

                            offset = clampZoomOffset(
                                offset = offset + adjustedPanDelta(dragAmount = dragAmount, scale = scale),
                                containerSize = containerSize,
                                scale = scale,
                            )
                            change.consume()
                        },
                    )
                }
                .pointerInput(containerSize) {
                    detectTapGestures(
                        onDoubleTap = { tapOffset ->
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = DOUBLE_TAP_ZOOM_SCALE
                                offset = doubleTapZoomOffset(
                                    containerSize = containerSize,
                                    tapOffset = tapOffset,
                                    scale = DOUBLE_TAP_ZOOM_SCALE,
                                )
                            }
                        },
                    )
                },
        )
    }
}

internal fun clampZoomOffset(
    offset: Offset,
    containerSize: Size,
    scale: Float,
): Offset {
    if (scale <= 1f) return Offset.Zero

    val maxX = ((containerSize.width * scale) - containerSize.width) / 2f
    val maxY = ((containerSize.height * scale) - containerSize.height) / 2f
    return Offset(
        x = offset.x.coerceIn(-maxX, maxX),
        y = offset.y.coerceIn(-maxY, maxY),
    )
}

internal fun doubleTapZoomOffset(
    containerSize: Size,
    tapOffset: Offset,
    scale: Float,
): Offset = clampZoomOffset(
    offset = Offset(
        x = (containerSize.width / 2f - tapOffset.x) * (scale - 1f),
        y = (containerSize.height / 2f - tapOffset.y) * (scale - 1f),
    ),
    containerSize = containerSize,
    scale = scale,
)

internal fun adjustedPanDelta(
    dragAmount: Offset,
    scale: Float,
): Offset {
    val multiplier = zoomedPanMultiplier(scale)
    return Offset(
        x = dragAmount.x * multiplier,
        y = dragAmount.y * multiplier,
    )
}

internal fun zoomedPanMultiplier(scale: Float): Float {
    if (scale <= 1f) return 1f
    return (scale * 0.8f).coerceAtMost(MAX_ZOOMED_PAN_MULTIPLIER)
}
