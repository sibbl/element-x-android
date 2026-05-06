/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.watchbridge.contract

private const val MAX_CUSTOM_VIBRATION_SEGMENTS = 16
private const val MAX_CUSTOM_VIBRATION_TOTAL_MS = 20_000L
private const val MAX_CUSTOM_VIBRATION_SEGMENT_MS = 5_000L
private val CUSTOM_VIBRATION_SEPARATOR_REGEX = "[\\s,;]+".toRegex()

/**
 * Parses a user-defined vibration string like `120 60 180 60 240` into a waveform timing array.
 * The returned array includes the leading `0` delay expected by `VibrationEffect.createWaveform`.
 */
fun parseCustomWatchNotificationVibrationPattern(spec: String?): LongArray? {
    val normalized = spec
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    val tokens = normalized.split(CUSTOM_VIBRATION_SEPARATOR_REGEX).filter { it.isNotBlank() }
    if (tokens.isEmpty() || tokens.size > MAX_CUSTOM_VIBRATION_SEGMENTS) return null

    val timings = LongArray(tokens.size + 1)
    timings[0] = 0L
    var totalDurationMs = 0L
    tokens.forEachIndexed { index, token ->
        val durationMs = token.toLongOrNull()
            ?.takeIf { it in 1L..MAX_CUSTOM_VIBRATION_SEGMENT_MS }
            ?: return null
        totalDurationMs += durationMs
        if (totalDurationMs > MAX_CUSTOM_VIBRATION_TOTAL_MS) return null
        timings[index + 1] = durationMs
    }
    return timings
}