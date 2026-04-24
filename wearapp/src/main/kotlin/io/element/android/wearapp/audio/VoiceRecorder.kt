/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** Voice-message recorder using the OGG/Opus preset used by Element X voice messages. */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var output: File? = null

    fun start(): File {
        val file = File(context.cacheDir, "wear_voice_${System.currentTimeMillis()}.ogg")
        output = file
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
        r.setAudioSamplingRate(16_000)
        r.setAudioChannels(1)
        r.setAudioEncodingBitRate(24_000)
        r.setOutputFile(file.absolutePath)
        r.prepare()
        r.start()
        recorder = r
        return file
    }

    fun stop(): File? {
        val r = recorder ?: return null
        runCatching { r.stop() }
        r.release()
        recorder = null
        return output
    }
}
