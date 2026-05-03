/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.watchbridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.element.android.x.R
import io.element.android.watchbridge.contract.WatchCompanionSettings
import io.element.android.watchbridge.contract.WatchLongPressConversationAction
import io.element.android.watchbridge.contract.WatchLongPressMessageAction

internal const val WEAR_COMPANION_SEND_TEST_NOTIFICATION_TAG = "wear-companion-send-test-notification"

internal data class WearCompanionNotificationSectionStrings(
    val sectionTitle: String,
    val actionTitle: String,
    val actionDescription: String,
)

/**
 * Standalone settings Activity for the Wear OS companion. Launched from the main app's preferences.
 * Follows the "additive-only" rule: no changes to existing preferences modules are required beyond
 * a single intent launch line.
 */
class WearCompanionSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = ElementXWatchBridgeRuntime.settingsStore(this)
        val notificationTester = WearCompanionTestNotificationSender(this)

        setContent {
            MaterialTheme {
                val settings by store.settings.collectAsState()
                WearCompanionSettingsScreen(
                    settings = settings,
                    onUpdateSettings = { store.update(it) },
                    onSendTestNotification = { notificationTester.send() },
                    onBack = { finish() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WearCompanionSettingsScreen(
    settings: WatchCompanionSettings,
    onUpdateSettings: (WatchCompanionSettings) -> Unit,
    onSendTestNotification: () -> Unit,
    onBack: () -> Unit,
    notificationStrings: WearCompanionNotificationSectionStrings? = null,
) {
    val resolvedNotificationStrings = notificationStrings ?: rememberWearCompanionNotificationSectionStrings()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wear OS Companion") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "Long press on messages",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            WatchLongPressMessageAction.entries.forEach { action ->
                ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onUpdateSettings(settings.copy(longPressMessageAction = action))
                        },
                    headlineContent = { Text(action.displayLabel()) },
                    leadingContent = {
                        RadioButton(
                            selected = settings.longPressMessageAction == action,
                            onClick = {
                                onUpdateSettings(settings.copy(longPressMessageAction = action))
                            },
                        )
                    },
                )
            }

            Text(
                text = "Long press on conversations",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            WatchLongPressConversationAction.entries.forEach { action ->
                ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onUpdateSettings(settings.copy(longPressConversationAction = action))
                        },
                    headlineContent = { Text(action.displayLabel()) },
                    leadingContent = {
                        RadioButton(
                            selected = settings.longPressConversationAction == action,
                            onClick = {
                                onUpdateSettings(settings.copy(longPressConversationAction = action))
                            },
                        )
                    },
                )
            }

            Text(
                text = resolvedNotificationStrings.sectionTitle,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onSendTestNotification)
                    .testTag(WEAR_COMPANION_SEND_TEST_NOTIFICATION_TAG),
                headlineContent = {
                    Text(resolvedNotificationStrings.actionTitle)
                },
                supportingContent = {
                    Text(resolvedNotificationStrings.actionDescription)
                },
            )
        }
    }
}

@Composable
private fun rememberWearCompanionNotificationSectionStrings(): WearCompanionNotificationSectionStrings =
    WearCompanionNotificationSectionStrings(
        sectionTitle = androidx.compose.ui.res.stringResource(R.string.screen_wear_companion_notifications_section),
        actionTitle = androidx.compose.ui.res.stringResource(R.string.screen_wear_companion_send_test_notification),
        actionDescription = androidx.compose.ui.res.stringResource(R.string.screen_wear_companion_send_test_notification_description),
    )

private fun WatchLongPressMessageAction.displayLabel(): String = when (this) {
    WatchLongPressMessageAction.READ_ALOUD -> "Read aloud / Play"
    WatchLongPressMessageAction.CREATE_THREAD -> "Create thread"
    WatchLongPressMessageAction.REPLY_EMOJI -> "Reply with emoji"
    WatchLongPressMessageAction.REPLY_TEXT -> "Reply with text"
    WatchLongPressMessageAction.REPLY_VOICE -> "Reply with voice message"
}

private fun WatchLongPressConversationAction.displayLabel(): String = when (this) {
    WatchLongPressConversationAction.READ_LATEST -> "Read latest message"
    WatchLongPressConversationAction.QUICK_REPLY_EMOJI -> "Quick reply with emoji"
    WatchLongPressConversationAction.QUICK_REPLY_TEXT -> "Quick reply with text"
    WatchLongPressConversationAction.QUICK_REPLY_VOICE -> "Quick reply with voice message"
    WatchLongPressConversationAction.OPEN_LATEST -> "Open latest message"
}
