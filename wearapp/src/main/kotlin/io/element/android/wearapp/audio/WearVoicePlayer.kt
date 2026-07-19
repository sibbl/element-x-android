/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Simple voice message player for the watch.
 */
class WearVoicePlayer {
    enum class State { IDLE, LOADING, PLAYING, ERROR }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private var player: MediaPlayer? = null

    fun play(context: Context, uri: Uri) {
        stop()
        _state.value = State.LOADING
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(context, uri)
                setOnPreparedListener {
                    _state.value = State.PLAYING
                    start()
                }
                setOnCompletionListener {
                    _state.value = State.IDLE
                    release()
                    player = null
                }
                setOnErrorListener { _, what, extra ->
                    Timber.w("Voice playback error: what=$what extra=$extra")
                    _state.value = State.ERROR
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to start voice playback")
            _state.value = State.ERROR
        }
    }

    fun stop() {
        try {
            player?.apply {
                if (isPlaying) stop()
                release()
            }
        } catch (_: Exception) { }
        player = null
        _state.value = State.IDLE
    }
}
