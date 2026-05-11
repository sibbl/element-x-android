/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.min

/** Lightweight Text-to-Speech wrapper used from the Room screen "read aloud" action. */
class WearTextToSpeech(context: Context) {

    enum class PlaybackState {
        IDLE,
        LOADING,
        PLAYING,
        PAUSED,
    }

    private val stateFlow = MutableStateFlow(PlaybackState.IDLE)
    private var ready = false
    private var released = false
    private var pendingStart = false
    private var text: String? = null
    private var segments = emptyList<String>()
    private var segmentIndex = 0
    private var generation = 0
    private var activeUtteranceId: String? = null
    private val engine: TextToSpeech

    val state: StateFlow<PlaybackState> = stateFlow.asStateFlow()

    init {
        synchronized(activeInstances) {
            activeInstances.add(this)
        }
        engine = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine.language = Locale.getDefault()
                engine.setOnUtteranceProgressListener(progressListener)
                val shouldStart = synchronized(this) {
                    if (released) {
                        false
                    } else {
                        ready = true
                        pendingStart.also { pendingStart = false }
                    }
                }
                if (shouldStart) {
                    speakCurrentSegment()
                }
            } else {
                synchronized(this) {
                    if (!released) {
                        stateFlow.value = PlaybackState.IDLE
                    }
                    pendingStart = false
                }
            }
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        val shouldStart = synchronized(this) {
            if (released) return
            this.text = text
            segments = text.toSpeechSegments()
            segmentIndex = 0
            generation += 1
            activeUtteranceId = null
            if (ready) {
                pendingStart = false
                true
            } else {
                pendingStart = true
                stateFlow.value = PlaybackState.LOADING
                false
            }
        }
        if (shouldStart) {
            engine.stop()
            speakCurrentSegment()
        }
    }

    fun toggle(text: String) {
        when (state.value) {
            PlaybackState.LOADING,
            PlaybackState.PLAYING -> pause()
            PlaybackState.PAUSED -> {
                if (this.text == text && segments.isNotEmpty()) {
                    resume()
                } else {
                    speak(text)
                }
            }
            PlaybackState.IDLE -> speak(text)
        }
    }

    fun pause() {
        val shouldStop = synchronized(this) {
            if (released) return
            when (stateFlow.value) {
                PlaybackState.LOADING,
                PlaybackState.PLAYING -> {
                    pendingStart = false
                    activeUtteranceId = null
                    stateFlow.value = if (segments.isEmpty()) PlaybackState.IDLE else PlaybackState.PAUSED
                    true
                }
                PlaybackState.IDLE,
                PlaybackState.PAUSED -> false
            }
        }
        if (shouldStop) {
            engine.stop()
        }
    }

    fun shutdown() {
        val shouldShutdown = synchronized(this) {
            if (released) {
                false
            } else {
                released = true
                pendingStart = false
                activeUtteranceId = null
                stateFlow.value = PlaybackState.IDLE
                true
            }
        }
        if (shouldShutdown) {
            synchronized(activeInstances) {
                activeInstances.remove(this)
            }
            engine.stop()
            engine.shutdown()
        }
    }

    private fun resume() {
        val shouldStart = synchronized(this) {
            if (released || segments.isEmpty()) return
            if (ready) {
                true
            } else {
                pendingStart = true
                stateFlow.value = PlaybackState.LOADING
                false
            }
        }
        if (shouldStart) {
            speakCurrentSegment()
        }
    }

    private fun speakCurrentSegment() {
        val request = synchronized(this) {
            if (released || !ready || segments.isEmpty()) return
            val utteranceId = "$generation:$segmentIndex"
            activeUtteranceId = utteranceId
            stateFlow.value = PlaybackState.LOADING
            SpeechRequest(
                text = segments[segmentIndex],
                utteranceId = utteranceId,
            )
        }
        val result = engine.speak(request.text, TextToSpeech.QUEUE_FLUSH, null, request.utteranceId)
        if (result == TextToSpeech.ERROR) {
            synchronized(this) {
                if (activeUtteranceId == request.utteranceId) {
                    activeUtteranceId = null
                    stateFlow.value = PlaybackState.IDLE
                }
            }
        }
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            synchronized(this@WearTextToSpeech) {
                if (!released && utteranceId == activeUtteranceId) {
                    stateFlow.value = PlaybackState.PLAYING
                }
            }
        }

        override fun onDone(utteranceId: String?) {
            val shouldContinue = synchronized(this@WearTextToSpeech) {
                if (released || utteranceId != activeUtteranceId) return
                activeUtteranceId = null
                val nextIndex = segmentIndex + 1
                if (nextIndex < segments.size) {
                    segmentIndex = nextIndex
                    true
                } else {
                    segments = emptyList()
                    text = null
                    segmentIndex = 0
                    stateFlow.value = PlaybackState.IDLE
                    false
                }
            }
            if (shouldContinue) {
                speakCurrentSegment()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            onError(utteranceId, TextToSpeech.ERROR)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            synchronized(this@WearTextToSpeech) {
                if (!released && utteranceId == activeUtteranceId) {
                    activeUtteranceId = null
                    stateFlow.value = PlaybackState.IDLE
                }
            }
        }
    }

    private data class SpeechRequest(
        val text: String,
        val utteranceId: String,
    )

    companion object {
        private val activeInstances = java.util.Collections.newSetFromMap(WeakHashMap<WearTextToSpeech, Boolean>())

        fun pauseAll() {
            val instances = synchronized(activeInstances) {
                activeInstances.toList()
            }
            instances.forEach { it.pause() }
        }
    }
}

private fun String.toSpeechSegments(): List<String> {
    val source = trim()
        .replace(Regex("[\\t\\x0B\\f\\r]+"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
    if (source.isBlank()) return emptyList()

    val maxLength = TextToSpeech.getMaxSpeechInputLength()
        .coerceAtMost(MAX_SPEECH_SEGMENT_LENGTH)
        .coerceAtLeast(1)
    if (source.length <= maxLength) return listOf(source)

    val chunks = mutableListOf<String>()
    var start = 0
    while (start < source.length) {
        val hardLimit = min(start + maxLength, source.length)
        val end = if (hardLimit == source.length) {
            hardLimit
        } else {
            source.findSpeechBoundary(start, hardLimit)
        }
        val chunk = source.substring(start, end).trim()
        if (chunk.isNotBlank()) {
            chunks += chunk
        }
        start = end
    }
    return chunks
}

private fun String.findSpeechBoundary(start: Int, hardLimit: Int): Int {
    val preferredMin = start + ((hardLimit - start) / 2)
    for (marker in SPEECH_BOUNDARY_MARKERS) {
        val boundary = lastIndexOf(marker, startIndex = hardLimit - 1)
        if (boundary >= preferredMin) {
            return (boundary + marker.length).coerceAtMost(hardLimit)
        }
    }
    return hardLimit
}

private const val MAX_SPEECH_SEGMENT_LENGTH = 3_500
private val SPEECH_BOUNDARY_MARKERS = listOf("\n\n", "\n", ". ", "! ", "? ", "; ", ", ", " ")
