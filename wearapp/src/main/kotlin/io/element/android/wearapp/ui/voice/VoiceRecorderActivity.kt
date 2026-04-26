/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui.voice

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import io.element.android.watchbridge.contract.WatchCommand
import io.element.android.watchbridge.contract.WatchDataPaths
import io.element.android.watchbridge.contract.WatchProtocol
import io.element.android.watchbridge.contract.WatchVoiceDraft
import io.element.android.wearapp.R
import io.element.android.wearapp.WearApp
import io.element.android.wearapp.audio.VoiceRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Dedicated screen for recording a voice message and handing it off to the phone via the
 * Wearable `ChannelClient` (bytes) + a `WatchCommand.UploadVoiceDraft` message (metadata).
 *
 * The phone persists the bytes and invokes Element X's existing voice send pipeline — nothing
 * about matrix-rust-sdk's voice flow is reimplemented on the watch.
 */
class VoiceRecorderActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result handled via state polling in Compose */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val roomId = intent.getStringExtra("roomId") ?: run { finish(); return }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            VoiceRecorderUi(
                roomId = roomId,
                onDone = { finish() },
                sendToPhone = { file, durationMs -> uploadToPhone(roomId, file, durationMs) },
            )
        }
    }

    private suspend fun uploadToPhone(roomId: String, file: File, durationMs: Long) =
        withContext(Dispatchers.IO) {
            val capability = Wearable.getCapabilityClient(this@VoiceRecorderActivity)
                .getCapability(WatchProtocol.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .await()
            val connectedNodes = Wearable.getNodeClient(this@VoiceRecorderActivity).connectedNodes.await()
            val node = (capability.nodes.takeIf { it.isNotEmpty() } ?: connectedNodes).nearbyFirst()
                ?: error("phone not reachable")
            val channelClient: ChannelClient = Wearable.getChannelClient(this@VoiceRecorderActivity)
            val channel = channelClient.openChannel(node.id, WatchDataPaths.VOICE_DRAFT_CHANNEL).await()
            try {
                val out = channelClient.getOutputStream(channel).await()
                file.inputStream().use { input -> input.copyTo(out) }
                out.flush()
                out.close()
            } finally {
                channelClient.close(channel).await()
            }

            val draft = WatchVoiceDraft(
                draftId = UUID.randomUUID().toString(),
                roomId = roomId,
                tempAudioUri = file.toURI().toString(),
                durationMs = durationMs,
                mimeType = "audio/ogg",
                sampleRateHz = 16_000,
                channelCount = 1,
                sizeBytes = file.length(),
            )
            (application as WearApp).bridgeClient.send {
                WatchCommand.UploadVoiceDraft(requestId = it, draft = draft)
            }
        }

    private fun Iterable<Node>.nearbyFirst(): Node? = firstOrNull { it.isNearby } ?: firstOrNull()
}

@Composable
private fun VoiceRecorderUi(
    roomId: String,
    onDone: () -> Unit,
    sendToPhone: suspend (File, Long) -> Unit,
) {
    val context = LocalContext.current
    val recorder = remember { VoiceRecorder(context) }
    var startedAt by remember { mutableStateOf<Long?>(null) }
    var recording by remember { mutableStateOf<File?>(null) }
    var sending by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(roomId)
        Chip(
            label = {
                Text(
                    if (startedAt != null) stringResource(R.string.stop)
                    else stringResource(R.string.record_voice),
                )
            },
            onClick = {
                if (startedAt == null) {
                    recorder.start()
                    startedAt = System.currentTimeMillis()
                } else {
                    recording = recorder.stop()
                }
            },
            colors = ChipDefaults.primaryChipColors(),
        )
        val file = recording
        val started = startedAt
        if (file != null && started != null && !sending) {
            Chip(
                label = { Text(stringResource(R.string.send)) },
                onClick = {},
                colors = ChipDefaults.primaryChipColors(),
            )
            LaunchedEffect(file) {
                sending = true
                runCatching { sendToPhone(file, System.currentTimeMillis() - started) }
                sending = false
                onDone()
            }
        }
    }
}
