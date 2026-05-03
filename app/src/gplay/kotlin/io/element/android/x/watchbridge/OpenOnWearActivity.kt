/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.watchbridge

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.wear.remote.interactions.RemoteActivityHelper
import io.element.android.appconfig.WearCompanionConfig
import io.element.android.appconfig.buildWearCompanionDeepLink
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import timber.log.Timber

class OpenOnWearActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val roomId = intent.getStringExtra(WearCompanionConfig.EXTRA_ROOM_ID)?.takeIf { it.isNotBlank() }
        if (roomId == null) {
            finish()
            return
        }

        val eventId = intent.getStringExtra(WearCompanionConfig.EXTRA_EVENT_ID)?.takeIf { it.isNotBlank() }
        val threadRootEventId = intent.getStringExtra(WearCompanionConfig.EXTRA_THREAD_ROOT_EVENT_ID)?.takeIf { it.isNotBlank() }

        lifecycleScope.launch {
            runCatching {
                RemoteActivityHelper(applicationContext)
                    .startRemoteActivity(
                        buildRemoteWearIntent(
                            roomId = roomId,
                            eventId = eventId,
                            threadRootEventId = threadRootEventId,
                        ),
                    )
                    .await()
            }.onFailure {
                Timber.w(
                    it,
                    "Failed to launch wear companion roomId=%s eventId=%s threadRootEventId=%s",
                    roomId,
                    eventId,
                    threadRootEventId,
                )
            }
            finish()
        }
    }
}

internal fun buildRemoteWearIntent(
    roomId: String,
    eventId: String? = null,
    threadRootEventId: String? = null,
): Intent {
    return Intent(Intent.ACTION_VIEW)
        .setData(
            buildWearCompanionDeepLink(
                roomId = roomId,
                eventId = eventId,
                threadRootEventId = threadRootEventId,
            ),
        )
        .addCategory(Intent.CATEGORY_BROWSABLE)
}