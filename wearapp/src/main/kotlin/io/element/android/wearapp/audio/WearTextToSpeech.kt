/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Lightweight Text-to-Speech wrapper used from the Room screen "read aloud" action. */
class WearTextToSpeech(context: Context) {

    private var ready = false
    private lateinit var engine: TextToSpeech

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            engine.language = Locale.getDefault()
            ready = true
        }
        }
    }

    fun speak(text: String) {
        if (!ready || text.isBlank()) return
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, text.hashCode().toString())
    }

    fun shutdown() {
        engine.stop()
        engine.shutdown()
    }
}
