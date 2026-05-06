/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import io.element.android.wearapp.R

/**
 * Compact composer surface used at the bottom of room and detail screens.
 *
 * Three primary actions:
 *  - [onReact] opens the emoji reaction picker (left).
 *  - [onReply] launches the platform reply UI (middle, highlighted).
 *  - [onVoice] starts a voice-message recording (right).
 *
 * Optional [contextLabel] is rendered above the buttons (e.g. "Reply to Alice").
 */
@Composable
fun ComposerBar(
    onReply: () -> Unit,
    onVoice: (() -> Unit)?,
    onReact: (() -> Unit)? = null,
    contextLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        contextLabel?.takeIf { it.isNotBlank() }?.let { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onReact != null) {
                FilledTonalIconButton(
                    modifier = Modifier.size(44.dp),
                    onClick = onReact,
                ) {
                    Icon(
                        imageVector = Icons.Filled.EmojiEmotions,
                        contentDescription = stringResource(R.string.a11y_composer_react),
                    )
                }
            }
            FilledIconButton(
                modifier = Modifier.size(44.dp),
                onClick = onReply,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Reply,
                    contentDescription = stringResource(R.string.a11y_composer_reply),
                )
            }
            if (onVoice != null) {
                FilledTonalIconButton(
                    modifier = Modifier.size(44.dp),
                    onClick = onVoice,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = stringResource(R.string.a11y_composer_voice),
                    )
                }
            }
        }
    }
}
