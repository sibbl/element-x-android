/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import io.element.android.watchbridge.contract.WatchVoiceDraft
import io.element.android.wearapp.R
import io.element.android.wearapp.WearApp
import io.element.android.wearapp.audio.VoiceRecorder
import io.element.android.wearapp.bridge.WearBridgeClient
import io.element.android.wearapp.ui.common.watchCommandErrorMessage
import io.element.android.wearapp.ui.theme.WearAppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Dedicated screen for recording a voice message and handing it off to the phone via the
 * Wearable `ChannelClient` (bytes) + a `WatchCommand.UploadVoiceDraft` message (metadata).
 *
 * The phone persists the bytes and invokes Element X's existing voice send pipeline — nothing
 * about matrix-rust-sdk's voice flow is reimplemented on the watch.
 */
class VoiceRecorderActivity : ComponentActivity() {

    private var hasRecordPermission by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasRecordPermission = granted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val roomId = intent.getStringExtra("roomId") ?: run { finish(); return }
        val threadRootEventId = intent.getStringExtra("threadRootEventId")
        val inReplyToEventId = intent.getStringExtra("inReplyToEventId")
        val roomDisplayNameExtra = intent.getStringExtra("roomDisplayName")
        val bridge = (application as WearApp).bridgeClient

        hasRecordPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasRecordPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            WearAppTheme {
                val favoriteRooms by bridge.favorites.collectAsState()
                val roomDisplayName = roomDisplayNameExtra
                    ?.takeIf { it.isNotBlank() }
                    ?: bridge.getCachedSummary(roomId)?.displayName
                    ?: favoriteRooms.firstOrNull { it.roomId == roomId }?.displayName
                    ?: stringResource(R.string.screen_room_loading_title)

                VoiceRecorderUi(
                    bridge = bridge,
                    roomId = roomId,
                    roomDisplayName = roomDisplayName,
                    threadRootEventId = threadRootEventId,
                    inReplyToEventId = inReplyToEventId,
                    hasRecordPermission = hasRecordPermission,
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    onDone = { finish() },
                )
            }
        }
    }
}

@Composable
private fun VoiceRecorderUi(
    bridge: WearBridgeClient,
    roomId: String,
    roomDisplayName: String,
    threadRootEventId: String?,
    inReplyToEventId: String?,
    hasRecordPermission: Boolean,
    onRequestPermission: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val recorder = remember { VoiceRecorder(context) }
    val scope = rememberCoroutineScope()
    var recordingStartedAt by remember { mutableStateOf<Long?>(null) }
    var elapsedMs by remember { mutableStateOf(0L) }
    var sending by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var autoStartRecording by remember { mutableStateOf(true) }
    val waveform = remember { mutableStateListOf<Int>() }

    DisposableEffect(recorder) {
        onDispose {
            recorder.cancel()
        }
    }

    LaunchedEffect(recordingStartedAt) {
        val startedAt = recordingStartedAt ?: return@LaunchedEffect
        while (recordingStartedAt != null) {
            elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
            waveform += recorder.currentAmplitude().toWaveformLevel()
            while (waveform.size > 96) {
                waveform.removeAt(0)
            }
            delay(90L)
        }
    }

    fun resetRecordingState() {
        recordingStartedAt = null
        elapsedMs = 0L
        waveform.clear()
    }

    fun beginRecording() {
        if (recordingStartedAt != null || sending) return
        runCatching {
            recorder.start()
        }.onSuccess {
            errorMessage = null
            waveform.clear()
            elapsedMs = 0L
            recordingStartedAt = System.currentTimeMillis()
        }.onFailure {
            errorMessage = context.watchCommandErrorMessage(it, R.string.watch_error_voice_send_failed)
        }
    }

    fun cancelRecording() {
        recorder.cancel()
        sending = false
        errorMessage = null
        resetRecordingState()
    }

    fun cancelAndClose() {
        cancelRecording()
        onDone()
    }

    LaunchedEffect(hasRecordPermission, autoStartRecording, recordingStartedAt, sending) {
        if (hasRecordPermission && autoStartRecording && recordingStartedAt == null && !sending) {
            autoStartRecording = false
            beginRecording()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = roomDisplayName,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.screen_voice_recorder_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                errorMessage?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                when {
                    sending -> {
                        CircularProgressIndicator(modifier = Modifier.size(44.dp))
                        Text(
                            text = stringResource(R.string.screen_voice_recorder_sending),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    !hasRecordPermission -> {
                        Text(
                            text = stringResource(R.string.screen_voice_recorder_permission),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    recordingStartedAt != null -> {
                        Text(
                            text = stringResource(R.string.screen_voice_recorder_recording),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        RecordingVisualizer(levels = waveform.takeLast(9))
                        Text(
                            text = elapsedMs.formatAsDuration(),
                            style = MaterialTheme.typography.numeralLarge,
                        )
                        Text(
                            text = stringResource(R.string.screen_voice_recorder_action_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    autoStartRecording -> {
                        CircularProgressIndicator(modifier = Modifier.size(38.dp))
                        Text(
                            text = stringResource(R.string.screen_voice_recorder_preparing),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                    }

                    else -> {
                        Text(
                            text = stringResource(R.string.screen_voice_recorder_preparing),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }

        when {
            sending -> Unit

            !hasRecordPermission -> {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        autoStartRecording = true
                        onRequestPermission()
                    },
                ) {
                    Text(stringResource(R.string.screen_voice_recorder_action_record))
                }
                FilledTonalButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = ::cancelAndClose,
                ) {
                    Text(stringResource(R.string.screen_voice_recorder_action_cancel))
                }
            }

            recordingStartedAt != null -> {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val file = recorder.stop()
                        val durationMs = elapsedMs.coerceAtLeast(1L)
                        val capturedWaveform = waveform.toList().compressWaveform()
                        resetRecordingState()
                        if (file == null) {
                            errorMessage = context.watchCommandErrorMessage(
                                IllegalStateException("missing recording"),
                                R.string.watch_error_voice_send_failed,
                            )
                            return@Button
                        }
                        scope.launch {
                            sending = true
                            errorMessage = null
                            runCatching {
                                bridge.uploadVoiceDraftAwaitTerminalAck(
                                    draft = WatchVoiceDraft(
                                        draftId = UUID.randomUUID().toString(),
                                        roomId = roomId,
                                        threadRootEventId = threadRootEventId,
                                        inReplyToEventId = inReplyToEventId,
                                        tempAudioUri = file.toURI().toString(),
                                        durationMs = durationMs,
                                        mimeType = "audio/ogg",
                                        sampleRateHz = 16_000,
                                        channelCount = 1,
                                        sizeBytes = file.length(),
                                        waveform = capturedWaveform,
                                    ),
                                    audioFile = file,
                                )
                            }.onSuccess {
                                onDone()
                            }.onFailure {
                                errorMessage = context.watchCommandErrorMessage(it, R.string.watch_error_voice_send_failed)
                            }
                            sending = false
                            file.delete()
                        }
                    },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.screen_voice_recorder_action_send))
                            Text(
                                text = elapsedMs.formatAsDuration(),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
                FilledTonalButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = ::cancelAndClose,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = null,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.screen_voice_recorder_action_cancel))
                    }
                }
            }

            else -> {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        autoStartRecording = false
                        beginRecording()
                    },
                ) {
                    Text(stringResource(R.string.screen_voice_recorder_action_retry))
                }
                FilledTonalButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = ::cancelAndClose,
                ) {
                    Text(stringResource(R.string.screen_voice_recorder_action_cancel))
                }
            }
        }
    }
}

@Composable
private fun RecordingVisualizer(levels: List<Int>) {
    val infiniteTransition = rememberInfiniteTransition(label = "voice-recorder-pulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "voice-recorder-pulse-alpha",
    )
    val bars = if (levels.isNotEmpty()) {
        levels.takeLast(7)
    } else {
        List(7) { index ->
            val wave = (sin((pulse + (index * 0.12f)) * PI) * 0.5f) + 0.5f
            (25 + (wave * 60)).roundToInt()
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = pulse), CircleShape),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            bars.forEach { level ->
                val barHeight = (16 + (level.coerceIn(0, 100) * 0.34f)).dp
                Box(
                    modifier = Modifier
                        .width(8.dp)
                        .height(barHeight)
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f + (level.coerceIn(0, 100) / 100f * 0.65f)),
                            shape = CircleShape,
                        ),
                )
            }
        }
    }
}

private fun Int.toWaveformLevel(): Int {
    if (this <= 0) return 0
    val normalized = (ln(toFloat() + 1f) / ln(32768f)).coerceIn(0f, 1f)
    return (normalized * 100f).roundToInt()
}

private fun List<Int>.compressWaveform(targetSize: Int = 48): List<Int> {
    if (isEmpty()) return emptyList()
    if (size <= targetSize) return this
    val bucketSize = size / targetSize.toFloat()
    return List(targetSize) { index ->
        val start = (index * bucketSize).toInt()
        val end = (((index + 1) * bucketSize).toInt()).coerceAtMost(size)
        subList(start, end).maxOrNull() ?: 0
    }
}

private fun Long.formatAsDuration(): String {
    val totalSeconds = (this / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
}
