/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.bridge

import com.google.common.truth.Truth.assertThat
import io.element.android.watchbridge.contract.WatchAck
import io.element.android.watchbridge.contract.WatchErrorCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class WearVoiceUploadProtocolTest {
    @Test
    fun `voice bytes upload only after phone reports awaiting audio channel`() = runTest {
        val acks = MutableSharedFlow<WatchAck>(extraBufferCapacity = 4)
        val actions = mutableListOf<String>()
        val upload = async {
            performVoiceDraftUpload(
                requestId = "voice-1",
                acks = acks,
                sendCommand = { actions += "command" },
                uploadBytes = { actions += "audio" },
            )
        }

        runCurrent()
        assertThat(actions).containsExactly("command")

        acks.emit(WatchAck.Pending(requestId = "voice-1", reason = "awaiting-audio-channel"))
        runCurrent()
        assertThat(actions).containsExactly("command", "audio").inOrder()

        acks.emit(WatchAck.Sent(requestId = "voice-1"))
        assertThat(upload.await()).isInstanceOf(WatchAck.Sent::class.java)
    }

    @Test
    fun `voice upload failure before pending does not send audio bytes`() = runTest {
        val acks = MutableSharedFlow<WatchAck>(extraBufferCapacity = 4)
        var uploaded = false
        val upload = async {
            runCatching {
                performVoiceDraftUpload(
                    requestId = "voice-2",
                    acks = acks,
                    sendCommand = { Unit },
                    uploadBytes = { uploaded = true },
                )
            }
        }

        runCurrent()
        acks.emit(WatchAck.Failed(requestId = "voice-2", code = WatchErrorCode.PHONE_APP_UNAVAILABLE))

        val failure = upload.await().exceptionOrNull()
        assertThat(failure).isInstanceOf(WatchCommandException::class.java)
        assertThat(uploaded).isFalse()
    }

    @Test
    fun `voice byte transfer closes stream before channel`() = runTest {
        val audioFile = File.createTempFile("wear-voice-transfer", ".ogg").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val actions = mutableListOf<String>()
        val output = object : ByteArrayOutputStream() {
            override fun close() {
                actions += "stream-closed"
                super.close()
            }
        }

        try {
            transferVoiceDraftBytes(
                audioFile = audioFile,
                openChannel = {
                    actions += "channel-opened"
                    "channel"
                },
                getOutputStream = {
                    actions += "stream-opened"
                    output
                },
                closeChannel = {
                    actions += "channel-closed"
                },
            )
        } finally {
            audioFile.delete()
        }

        assertThat(output.toByteArray().toList()).containsExactly(1.toByte(), 2.toByte(), 3.toByte(), 4.toByte()).inOrder()
        assertThat(actions).containsExactly(
            "channel-opened",
            "stream-opened",
            "stream-closed",
            "channel-closed",
        ).inOrder()
    }

    @Test
    fun `voice byte transfer closes channel when writing fails`() = runTest {
        val audioFile = File.createTempFile("wear-voice-transfer", ".ogg").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        var channelClosed = false

        try {
            val result = runCatching {
                transferVoiceDraftBytes(
                    audioFile = audioFile,
                    openChannel = { "channel" },
                    getOutputStream = {
                        object : ByteArrayOutputStream() {
                            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                                throw java.io.IOException("transfer failed")
                            }
                        }
                    },
                    closeChannel = { channelClosed = true },
                )
            }

            assertThat(result.exceptionOrNull()).isInstanceOf(java.io.IOException::class.java)
            assertThat(channelClosed).isTrue()
        } finally {
            audioFile.delete()
        }
    }
}
