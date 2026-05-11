/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui.common

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

@Composable
internal fun AvatarBadge(
    displayName: String,
    avatarUrl: String?,
    avatarBytes: ByteArray? = null,
    modifier: Modifier = Modifier,
) {
    val fallbackColor = remember(displayName) { avatarPalette(displayName) }
    val decodedAvatar = remember(avatarBytes) {
        avatarBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(fallbackColor)
            .border(width = androidx.compose.ui.unit.Dp.Hairline, color = Color.White.copy(alpha = 0.18f), shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (decodedAvatar != null) {
            Image(
                bitmap = decodedAvatar,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = displayName.toInitials(),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

internal fun String.toInitials(): String {
    val parts = trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
    return when {
        parts.size >= 2 -> "${parts.first().first()}${parts.last().first()}"
        parts.size == 1 -> parts.first().take(2)
        else -> "?"
    }.uppercase()
}

private fun avatarPalette(seed: String): Color {
    val colors = listOf(
        Color(0xFF5B8DEF),
        Color(0xFF8E64FF),
        Color(0xFF1FA37A),
        Color(0xFFE07A2D),
        Color(0xFFC94F7C),
        Color(0xFF4C9BCE),
    )
    val index = seed.hashCode().mod(colors.size).let { if (it < 0) it + colors.size else it }
    return colors[index]
}
