/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import io.element.android.watchbridge.contract.WatchCommand
import io.element.android.watchbridge.contract.WatchLongPressConversationAction
import io.element.android.watchbridge.contract.WatchSendSource
import io.element.android.wearapp.WearApp
import io.element.android.wearapp.R
import io.element.android.wearapp.audio.WearTextToSpeech
import io.element.android.wearapp.ui.common.watchCommandErrorMessage
import io.element.android.wearapp.ui.room.ImageViewerScreen
import io.element.android.wearapp.ui.favorites.FavoritesScreen
import io.element.android.wearapp.ui.room.MessageDetailScreen
import io.element.android.wearapp.ui.room.RoomScreen
import io.element.android.wearapp.ui.thread.ThreadScreen
import io.element.android.wearapp.ui.voice.VoiceRecorderActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** Top-level watch activity. Pure Compose for Wear; Swipe-to-dismiss navigation. */
class WearMainActivity : ComponentActivity() {

    private var pendingDictationResult: ((String?) -> Unit)? = null
    private var pendingDeepLink by mutableStateOf<Pair<String, String?>?>(null)
    private var pendingRoomScrollRequest by mutableStateOf<PendingRoomScrollRequest?>(null)
    private var transientErrorMessage by mutableStateOf<String?>(null)

    private val dictationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val text = if (result.resultCode == Activity.RESULT_OK) {
            result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
        } else null
        pendingDictationResult?.invoke(text)
        pendingDictationResult = null
    }

    fun launchDictation(onResult: (String?) -> Unit) {
        pendingDictationResult = onResult
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        dictationLauncher.launch(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingDeepLink = consumePendingDeepLink(intent)
        val bridge = (application as WearApp).bridgeClient
        setContent {
            val nav = rememberSwipeDismissableNavController()
            LaunchedEffect(pendingDeepLink) {
                pendingDeepLink?.let { (roomId, eventId) ->
                    if (eventId != null) {
                        nav.navigate("message?roomId=${Uri.encode(roomId)}&eventId=${Uri.encode(eventId)}")
                    } else {
                        nav.navigate("room?roomId=${Uri.encode(roomId)}")
                    }
                    pendingDeepLink = null
                }
            }
            LaunchedEffect(transientErrorMessage) {
                if (transientErrorMessage != null) {
                    delay(4_000L)
                    transientErrorMessage = null
                }
            }
            MaterialTheme {
                Box {
                    SwipeDismissableNavHost(navController = nav, startDestination = "favorites") {
                        composable("favorites") {
                            val settings by bridge.companionSettings.collectAsState()
                            val scope = rememberCoroutineScope()
                            val tts = remember { WearTextToSpeech(this@WearMainActivity) }
                            FavoritesScreen(
                                bridge = bridge,
                                onRoomSelected = { roomId ->
                                    nav.navigate("room?roomId=${Uri.encode(roomId)}")
                                },
                                onLongPressRoom = { room ->
                                    when (settings.longPressConversationAction) {
                                        WatchLongPressConversationAction.READ_LATEST -> {
                                            room.lastPreviewText?.let { tts.speak(it) }
                                        }
                                        WatchLongPressConversationAction.QUICK_REPLY_EMOJI -> {
                                            nav.navigate("room?roomId=${Uri.encode(room.roomId)}")
                                        }
                                        WatchLongPressConversationAction.QUICK_REPLY_TEXT -> {
                                            launchDictation { dictated ->
                                                if (!dictated.isNullOrBlank()) {
                                                    scope.launch {
                                                        runCatching {
                                                            bridge.sendAwaitTerminalAck {
                                                                WatchCommand.SendText(
                                                                    requestId = it,
                                                                    roomId = room.roomId,
                                                                    text = dictated,
                                                                    source = WatchSendSource.DICTATION,
                                                                    clientTsMs = System.currentTimeMillis(),
                                                                )
                                                            }
                                                        }.onFailure {
                                                            transientErrorMessage = this@WearMainActivity.watchCommandErrorMessage(
                                                                it,
                                                                R.string.watch_error_send_failed,
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        WatchLongPressConversationAction.QUICK_REPLY_VOICE -> {
                                            startActivity(
                                                Intent(this@WearMainActivity, VoiceRecorderActivity::class.java)
                                                    .putExtra("roomId", room.roomId)
                                                    .putExtra("roomDisplayName", room.displayName),
                                            )
                                        }
                                        WatchLongPressConversationAction.OPEN_LATEST -> {
                                            nav.navigate("room?roomId=${Uri.encode(room.roomId)}")
                                        }
                                    }
                                },
                                onError = { transientErrorMessage = it },
                            )
                        }
                        composable("room?roomId={roomId}") { entry ->
                            val roomId = entry.arguments?.getString("roomId")?.let(Uri::decode) ?: return@composable
                            val roomScrollRequest = pendingRoomScrollRequest?.takeIf { it.roomId == roomId }
                            RoomScreen(
                                bridge = bridge,
                                roomId = roomId,
                                activity = this@WearMainActivity,
                                onOpenThread = { rootId ->
                                    nav.navigate(
                                        "thread?roomId=${Uri.encode(roomId)}&rootId=${Uri.encode(rootId)}",
                                    )
                                },
                                onMessageSelected = { eventId ->
                                    nav.navigate(
                                        "message?roomId=${Uri.encode(roomId)}&eventId=${Uri.encode(eventId)}",
                                    )
                                },
                                scrollRequestId = roomScrollRequest?.requestId,
                                scrollToEventId = roomScrollRequest?.targetEventId,
                                forceScrollToBottom = roomScrollRequest?.forceScrollToBottom == true,
                                onScrollRequestHandled = { requestId ->
                                    if (pendingRoomScrollRequest?.requestId == requestId) {
                                        pendingRoomScrollRequest = null
                                    }
                                },
                                onError = { transientErrorMessage = it },
                            )
                        }
                        composable("message?roomId={roomId}&eventId={eventId}") { entry ->
                            val roomId = entry.arguments?.getString("roomId")?.let(Uri::decode) ?: return@composable
                            val eventId = entry.arguments?.getString("eventId")?.let(Uri::decode) ?: return@composable
                            MessageDetailScreen(
                                bridge = bridge,
                                roomId = roomId,
                                eventId = eventId,
                                activity = this@WearMainActivity,
                                onOpenThread = { rootId ->
                                    nav.navigate(
                                        "thread?roomId=${Uri.encode(roomId)}&rootId=${Uri.encode(rootId)}",
                                    )
                                },
                                onOpenImage = { imageEventId ->
                                    nav.navigate(
                                        "image?roomId=${Uri.encode(roomId)}&eventId=${Uri.encode(imageEventId)}",
                                    )
                                },
                                onReplySent = { sourceEventId, sourceWasLastMessage ->
                                    pendingRoomScrollRequest = PendingRoomScrollRequest(
                                        roomId = roomId,
                                        targetEventId = sourceEventId.takeUnless { sourceWasLastMessage },
                                        forceScrollToBottom = sourceWasLastMessage,
                                    )
                                    nav.popBackStack()
                                },
                                onError = { transientErrorMessage = it },
                            )
                        }
                        composable("image?roomId={roomId}&eventId={eventId}") { entry ->
                            val roomId = entry.arguments?.getString("roomId")?.let(Uri::decode) ?: return@composable
                            val eventId = entry.arguments?.getString("eventId")?.let(Uri::decode) ?: return@composable
                            ImageViewerScreen(
                                bridge = bridge,
                                roomId = roomId,
                                eventId = eventId,
                            )
                        }
                        composable("thread?roomId={roomId}&rootId={rootId}") { entry ->
                            val roomId = entry.arguments?.getString("roomId")?.let(Uri::decode) ?: return@composable
                            val rootId = entry.arguments?.getString("rootId")?.let(Uri::decode) ?: return@composable
                            ThreadScreen(
                                bridge = bridge,
                                roomId = roomId,
                                threadRootEventId = rootId,
                                activity = this@WearMainActivity,
                                onError = { transientErrorMessage = it },
                            )
                        }
                    }

                    transientErrorMessage?.let { message ->
                        Text(
                            text = message,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                                .background(
                                    color = MaterialTheme.colors.error,
                                    shape = RoundedCornerShape(14.dp),
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            color = MaterialTheme.colors.onError,
                            style = MaterialTheme.typography.caption2,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // When already running and a notification opens us, store deep link for next recomposition.
        pendingDeepLink = consumePendingDeepLink(intent)
    }

    private data class PendingRoomScrollRequest(
        val roomId: String,
        val requestId: Long = System.currentTimeMillis(),
        val targetEventId: String? = null,
        val forceScrollToBottom: Boolean = false,
    )
}

internal fun consumePendingDeepLink(intent: Intent?): Pair<String, String?>? {
    val roomId = intent?.getStringExtra("roomId")?.takeIf { it.isNotBlank() } ?: return null
    val eventId = intent.getStringExtra("eventId")?.takeIf { it.isNotBlank() }
    intent.removeExtra("roomId")
    intent.removeExtra("eventId")
    return roomId to eventId
}
