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
}
