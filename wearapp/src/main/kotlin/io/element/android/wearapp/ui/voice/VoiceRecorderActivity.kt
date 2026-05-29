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
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import io.element.android.watchbridge.contract.WatchVoiceDraft
import io.element.android.wearapp.R
import io.element.android.wearapp.WearApp
import io.element.android.wearapp.audio.VoiceRecorder
import io.element.android.wearapp.bridge.WearBridgeClient
import io.element.android.wearapp.ui.buildWearLaunchIntent
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
        val roomId = intent.getStringExtra("roomId") ?: run {
            finish()
            return
        }
        val threadRootEventId = intent.getStringExtra("threadRootEventId")
        val inReplyToEventId = intent.getStringExtra("inReplyToEventId")
        val returnToEventId = intent.getStringExtra(EXTRA_RETURN_TO_EVENT_ID)
        val bridge = (application as WearApp).bridgeClient

        hasRecordPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasRecordPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            WearAppTheme {
                VoiceRecorderUi(
                    bridge = bridge,
                    roomId = roomId,
                    threadRootEventId = threadRootEventId,
                    inReplyToEventId = inReplyToEventId,
                    returnToEventId = returnToEventId,
                    hasRecordPermission = hasRecordPermission,
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    onRecordingActiveChanged = ::setRecordingKeepScreenOn,
                    onDone = { shouldOpenMessageDetail ->
                        if (shouldOpenMessageDetail && returnToEventId != null) {
                            startActivity(
                                buildWearLaunchIntent(
                                    context = this,
                                    roomId = roomId,
                                    eventId = returnToEventId,
                                    threadRootEventId = threadRootEventId,
                                ),
                            )
                        }
                        finish()
                    },
                )
            }
        }
    }

    private fun setRecordingKeepScreenOn(active: Boolean) {
        if (active) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    internal companion object {
        const val EXTRA_RETURN_TO_EVENT_ID = "returnToEventId"
    }
}

@Composable
private fun VoiceRecorderUi(
    bridge: WearBridgeClient,
    roomId: String,
    threadRootEventId: String?,
    inReplyToEventId: String?,
    returnToEventId: String?,
    hasRecordPermission: Boolean,
    onRequestPermission: () -> Unit,
    onRecordingActiveChanged: (Boolean) -> Unit,
    onDone: (shouldOpenMessageDetail: Boolean) -> Unit,
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
            onRecordingActiveChanged(false)
            recorder.cancel()
        }
    }

    LaunchedEffect(recordingStartedAt) {
        onRecordingActiveChanged(recordingStartedAt != null)
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
        onDone(false)
    }

    LaunchedEffect(hasRecordPermission, autoStartRecording, recordingStartedAt, sending) {
        if (hasRecordPermission && autoStartRecording && recordingStartedAt == null && !sending) {
            beginRecording()
            autoStartRecording = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        errorMessage?.takeIf { !sending && recordingStartedAt == null }?.let { message ->
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
                CircularProgressIndicator(modifier = Modifier.size(40.dp))
            }

            !hasRecordPermission -> {
                Text(
                    text = stringResource(R.string.screen_voice_recorder_permission),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalIconButton(
                        modifier = Modifier.size(48.dp),
                        onClick = ::cancelAndClose,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.screen_voice_recorder_action_cancel),
                        )
                    }
                    FilledIconButton(
                        modifier = Modifier.size(48.dp),
                        onClick = {
                            autoStartRecording = true
                            onRequestPermission()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.screen_voice_recorder_action_record),
                        )
                    }
                }
            }

            else -> {
                Text(
                    text = elapsedMs.formatAsDuration(),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    RecordingVisualizer(
                        levels = waveform.takeLast(9),
                        modifier = Modifier.height(72.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalIconButton(
                        modifier = Modifier.size(48.dp),
                        onClick = ::cancelAndClose,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.screen_voice_recorder_action_cancel),
                        )
                    }
                    FilledIconButton(
                        modifier = Modifier.size(48.dp),
                        enabled = recordingStartedAt != null,
                        onClick = {
                            val file = recorder.stop()
                            val durationMs = elapsedMs.coerceAtLeast(1L)
                            val capturedWaveform = waveform.toList().compressWaveform()
                            sending = true
                            resetRecordingState()
                            if (file == null) {
                                sending = false
                                errorMessage = context.watchCommandErrorMessage(
                                    IllegalStateException("missing recording"),
                                    R.string.watch_error_voice_send_failed,
                                )
                                return@FilledIconButton
                            }
                            scope.launch {
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
                                    onDone(returnToEventId != null)
                                }.onFailure {
                                    errorMessage = context.watchCommandErrorMessage(it, R.string.watch_error_voice_send_failed)
                                    sending = false
                                }
                                file.delete()
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.screen_voice_recorder_action_send),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingVisualizer(
    levels: List<Int>,
    modifier: Modifier = Modifier,
) {
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

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = pulse), CircleShape),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            bars.forEach { level ->
                val barHeight = (12 + (level.coerceIn(0, 100) * 0.28f)).dp
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
