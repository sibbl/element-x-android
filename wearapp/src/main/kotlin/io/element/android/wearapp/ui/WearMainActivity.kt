/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import io.element.android.wearapp.WearApp
import io.element.android.wearapp.ui.favorites.FavoritesScreen
import io.element.android.wearapp.ui.room.RoomScreen
import io.element.android.wearapp.ui.thread.ThreadScreen
import java.util.Locale

/** Top-level watch activity. Pure Compose for Wear; Swipe-to-dismiss navigation. */
class WearMainActivity : ComponentActivity() {

    private var pendingDictationResult: ((String?) -> Unit)? = null

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
        val bridge = (application as WearApp).bridgeClient
        setContent {
            val nav = rememberSwipeDismissableNavController()
            SwipeDismissableNavHost(navController = nav, startDestination = "favorites") {
                composable("favorites") {
                    FavoritesScreen(
                        bridge = bridge,
                        onRoomSelected = { roomId -> nav.navigate("room/$roomId") },
                    )
                }
                composable("room/{roomId}") { entry ->
                    val roomId = entry.arguments?.getString("roomId") ?: return@composable
                    RoomScreen(
                        bridge = bridge,
                        roomId = roomId,
                        activity = this@WearMainActivity,
                        onOpenThread = { rootId -> nav.navigate("thread/$roomId/$rootId") },
                    )
                }
                composable("thread/{roomId}/{rootId}") { entry ->
                    val roomId = entry.arguments?.getString("roomId") ?: return@composable
                    val rootId = entry.arguments?.getString("rootId") ?: return@composable
                    ThreadScreen(
                        bridge = bridge,
                        roomId = roomId,
                        threadRootEventId = rootId,
                        activity = this@WearMainActivity,
                    )
                }
            }
        }
    }
}
