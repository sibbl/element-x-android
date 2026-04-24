/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text
import io.element.android.wearapp.R

/**
 * Minimal composer bar for watch screens. Intentionally tiny: dictation is primary, keyboard is a
 * fallback via the stock IME. Voice-message recording is an optional button.
 */
@Composable
fun ComposerBar(
    onSend: (String) -> Unit,
    onDictate: () -> Unit,
    onVoice: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Chip(
            label = { Text(stringResource(R.string.dictate)) },
            onClick = onDictate,
            colors = ChipDefaults.primaryChipColors(),
        )
        if (onVoice != null) {
            Chip(
                label = { Text(stringResource(R.string.record_voice)) },
                onClick = onVoice,
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
    }
}
