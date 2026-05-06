/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import io.element.android.appconfig.WearCompanionDeepLink
import io.element.android.watchbridge.contract.WatchCommand
import io.element.android.watchbridge.contract.WatchLongPressConversationAction
import io.element.android.watchbridge.contract.WatchSendSource
import io.element.android.wearapp.WearApp
import io.element.android.wearapp.R
import io.element.android.wearapp.audio.WearTextToSpeech
import io.element.android.wearapp.ui.common.watchCommandErrorMessage
import io.element.android.wearapp.ui.room.ImageViewerScreen
import io.element.android.wearapp.ui.favorites.FavoritesScreen
import io.element.android.wearapp.ui.favorites.SavedScalingListPosition
import io.element.android.wearapp.ui.room.MessageDetailScreen
import io.element.android.wearapp.ui.room.RoomScreen
import io.element.android.wearapp.ui.thread.ThreadScreen
import io.element.android.wearapp.ui.theme.WearAppTheme
import io.element.android.wearapp.ui.voice.VoiceRecorderActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** Top-level watch activity. Pure Compose for Wear; Swipe-to-dismiss navigation. */
class WearMainActivity : ComponentActivity() {

    private var pendingDictationResult: ((String?) -> Unit)? = null
    private var notificationPermissionState by mutableStateOf(WearNotificationPermissionState.Granted)
    private var pendingDeepLink by mutableStateOf<WearCompanionDeepLink?>(null)
    private var pendingTileDirectReplyRoomId by mutableStateOf<String?>(null)
    private var pendingRoomScrollRequest by mutableStateOf<PendingRoomScrollRequest?>(null)
    private var transientErrorMessage by mutableStateOf<String?>(null)
    private var favoritesRequestedRoomCount by mutableIntStateOf(30)
    private var favoritesRestoredPage by mutableIntStateOf(-1)
    private val favoritesListPositions = mutableStateMapOf<String, SavedScalingListPosition>()
    private val roomListPositions = mutableStateMapOf<String, SavedScalingListPosition>()
    private val threadListPositions = mutableStateMapOf<String, SavedScalingListPosition>()
    private var hasRequestedNotificationPermissionThisSession = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        refreshNotificationPermissionState()
    }

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
        refreshNotificationPermissionState()
        pendingDeepLink = consumePendingDeepLink(intent)
        pendingTileDirectReplyRoomId = consumePendingTileDirectReplyRoomId(intent)
        val bridge = (application as WearApp).bridgeClient
        setContent {
            val nav = rememberSwipeDismissableNavController()
            val scope = rememberCoroutineScope()
            val openRoomAtBottom: (String) -> Unit = { roomId ->
                pendingRoomScrollRequest = PendingRoomScrollRequest(
                    roomId = roomId,
                    forceScrollToBottom = true,
                )
                nav.navigate(roomRoute(roomId)) {
                    launchSingleTop = true
                }
            }
            LaunchedEffect(notificationPermissionState) {
                requestNotificationPermissionIfNeeded()
            }
            LaunchedEffect(pendingDeepLink) {
                pendingDeepLink?.let { deepLink ->
                    when {
                        deepLink.threadRootEventId != null -> {
                            navigateFromRoot(nav = nav, deepLink = deepLink)
                        }
                        deepLink.eventId == null -> {
                            openRoomAtBottom(deepLink.roomId)
                        }
                        else -> {
                            navigateFromRoot(nav = nav, deepLink = deepLink)
                        }
                    }
                    pendingDeepLink = null
                }
            }
            LaunchedEffect(pendingTileDirectReplyRoomId) {
                pendingTileDirectReplyRoomId?.let { roomId ->
                    launchDictation { dictated ->
                        if (!dictated.isNullOrBlank()) {
                            scope.launch {
                                runCatching {
                                    bridge.sendAwaitTerminalAck {
                                        WatchCommand.SendText(
                                            requestId = it,
                                            roomId = roomId,
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
                    pendingTileDirectReplyRoomId = null
                }
            }
            LaunchedEffect(transientErrorMessage) {
                if (transientErrorMessage != null) {
                    delay(4_000L)
                    transientErrorMessage = null
                }
            }
            WearAppTheme {
                AppScaffold {
                    Box {
                        SwipeDismissableNavHost(navController = nav, startDestination = "favorites") {
                            composable("favorites") {
                                val settings by bridge.companionSettings.collectAsState()
                                val scope = rememberCoroutineScope()
                                val tts = remember { WearTextToSpeech(this@WearMainActivity) }
                                FavoritesScreen(
                                    bridge = bridge,
                                    onRoomSelected = openRoomAtBottom,
                                    onLongPressRoom = { room ->
                                        when (settings.longPressConversationAction) {
                                            WatchLongPressConversationAction.READ_LATEST -> {
                                                room.lastPreviewText?.let { tts.speak(it) }
                                            }
                                            WatchLongPressConversationAction.QUICK_REPLY_EMOJI -> {
                                                openRoomAtBottom(room.roomId)
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
                                                openRoomAtBottom(room.roomId)
                                            }
                                        }
                                    },
                                    onError = { transientErrorMessage = it },
                                    requestedRoomCount = favoritesRequestedRoomCount,
                                    restoredPage = favoritesRestoredPage.takeIf { it >= 0 },
                                    savedFavoriteListPosition = favoritesListPositions[FAVORITES_PAGE_KEY],
                                    savedRecentListPosition = favoritesListPositions[RECENTS_PAGE_KEY],
                                    onRequestedRoomCountChange = { favoritesRequestedRoomCount = it.coerceAtLeast(favoritesRequestedRoomCount) },
                                    onPageChanged = { favoritesRestoredPage = it },
                                    onFavoriteListPositionChange = { favoritesListPositions[FAVORITES_PAGE_KEY] = it },
                                    onRecentListPositionChange = { favoritesListPositions[RECENTS_PAGE_KEY] = it },
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
                                        nav.navigate(threadRoute(roomId = roomId, rootId = rootId)) {
                                            launchSingleTop = true
                                        }
                                    },
                                    onMessageSelected = { eventId ->
                                        nav.navigate(messageRoute(roomId = roomId, eventId = eventId)) {
                                            launchSingleTop = true
                                        }
                                    },
                                    scrollRequestId = roomScrollRequest?.requestId,
                                    scrollToEventId = roomScrollRequest?.targetEventId,
                                    forceScrollToBottom = roomScrollRequest?.forceScrollToBottom == true,
                                    onScrollRequestHandled = { requestId ->
                                        if (pendingRoomScrollRequest?.requestId == requestId) {
                                            pendingRoomScrollRequest = null
                                        }
                                    },
                                    savedListPosition = roomListPositions[roomId],
                                    onListPositionChange = { roomListPositions[roomId] = it },
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
                                        nav.navigate(threadRoute(roomId = roomId, rootId = rootId)) {
                                            launchSingleTop = true
                                        }
                                    },
                                    onOpenImage = { imageEventId ->
                                        nav.navigate(imageRoute(roomId = roomId, eventId = imageEventId)) {
                                            launchSingleTop = true
                                        }
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
                                    savedListPosition = threadListPositions["$roomId/$rootId"],
                                    onListPositionChange = { threadListPositions["$roomId/$rootId"] = it },
                                    onError = { transientErrorMessage = it },
                                )
                            }
                        }
                    }

                    if (notificationPermissionState != WearNotificationPermissionState.Granted) {
                        NotificationPermissionPrompt(
                            state = notificationPermissionState,
                            onRequestPermission = { requestNotificationPermissionIfNeeded(force = true) },
                            onOpenSettings = ::openNotificationSettings,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }

                    transientErrorMessage?.let { message ->
                        Text(
                            text = message,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    shape = RoundedCornerShape(14.dp),
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshNotificationPermissionState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // When already running and a notification opens us, store deep link for next recomposition.
        pendingDeepLink = consumePendingDeepLink(intent)
        pendingTileDirectReplyRoomId = consumePendingTileDirectReplyRoomId(intent)
    }

    private fun refreshNotificationPermissionState() {
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        notificationPermissionState = resolveWearNotificationPermissionState(
            sdkInt = Build.VERSION.SDK_INT,
            permissionGranted = permissionGranted,
            notificationsEnabled = notificationsEnabled,
        )
    }

    private fun requestNotificationPermissionIfNeeded(force: Boolean = false) {
        if (notificationPermissionState != WearNotificationPermissionState.NeedsRuntimePermission) return
        if (!force && hasRequestedNotificationPermissionThisSession) return
        hasRequestedNotificationPermissionThisSession = true
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openNotificationSettings() {
        val notificationSettingsIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        val appDetailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", packageName, null))

        try {
            startActivity(notificationSettingsIntent)
        } catch (_: ActivityNotFoundException) {
            startActivity(appDetailsIntent)
        }
    }

    private data class PendingRoomScrollRequest(
        val roomId: String,
        val requestId: Long = System.currentTimeMillis(),
        val targetEventId: String? = null,
        val forceScrollToBottom: Boolean = false,
    )
}

private const val FAVORITES_PAGE_KEY = "favorites"
private const val RECENTS_PAGE_KEY = "recents"

internal enum class WearNotificationPermissionState {
    Granted,
    NeedsRuntimePermission,
    DisabledInSettings,
}

internal fun resolveWearNotificationPermissionState(
    sdkInt: Int,
    permissionGranted: Boolean,
    notificationsEnabled: Boolean,
): WearNotificationPermissionState {
    return when {
        sdkInt >= Build.VERSION_CODES.TIRAMISU && !permissionGranted -> WearNotificationPermissionState.NeedsRuntimePermission
        !notificationsEnabled -> WearNotificationPermissionState.DisabledInSettings
        else -> WearNotificationPermissionState.Granted
    }
}

@Composable
private fun NotificationPermissionPrompt(
    state: WearNotificationPermissionState,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val messageResId: Int
    val actionResId: Int
    val action: () -> Unit

    when (state) {
        WearNotificationPermissionState.Granted -> return
        WearNotificationPermissionState.NeedsRuntimePermission -> {
            messageResId = R.string.screen_wear_notifications_permission_message
            actionResId = R.string.screen_wear_notifications_action_allow
            action = onRequestPermission
        }
        WearNotificationPermissionState.DisabledInSettings -> {
            messageResId = R.string.screen_wear_notifications_settings_message
            actionResId = R.string.screen_wear_notifications_action_settings
            action = onOpenSettings
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(messageResId),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = action,
        ) {
            Text(
                text = stringResource(actionResId),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun roomRoute(roomId: String): String = "room?roomId=${Uri.encode(roomId)}"

private fun messageRoute(roomId: String, eventId: String): String =
    "message?roomId=${Uri.encode(roomId)}&eventId=${Uri.encode(eventId)}"

private fun imageRoute(roomId: String, eventId: String): String =
    "image?roomId=${Uri.encode(roomId)}&eventId=${Uri.encode(eventId)}"

private fun threadRoute(roomId: String, rootId: String): String =
    "thread?roomId=${Uri.encode(roomId)}&rootId=${Uri.encode(rootId)}"

private fun navigateFromRoot(
    nav: androidx.navigation.NavHostController,
    deepLink: WearCompanionDeepLink,
) {
    val threadRootEventId = deepLink.threadRootEventId
    val eventId = deepLink.eventId
    val route = when {
        threadRootEventId != null -> threadRoute(
            roomId = deepLink.roomId,
            rootId = threadRootEventId,
        )
        eventId != null -> messageRoute(deepLink.roomId, eventId)
        else -> roomRoute(deepLink.roomId)
    }
    nav.navigate(route) {
        popUpTo("favorites") {
            inclusive = false
        }
        launchSingleTop = true
    }
}
