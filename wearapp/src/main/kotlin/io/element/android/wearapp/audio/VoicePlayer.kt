/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.audio

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri

/** Minimal playback wrapper for voice messages streamed from the phone. */
class VoicePlayer(private val context: Context) {

    private var player: MediaPlayer? = null

    fun play(uri: Uri, onCompletion: () -> Unit = {}) {
        stop()
        player = MediaPlayer().apply {
            setDataSource(context, uri)
            setOnCompletionListener { onCompletion() }
            prepare()
            start()
        }
    }

    fun stop() {
        player?.runCatching { stop() }
        player?.release()
        player = null
    }
}
