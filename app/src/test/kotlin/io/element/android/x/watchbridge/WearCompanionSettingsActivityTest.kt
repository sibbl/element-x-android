/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.watchbridge

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import com.google.common.truth.Truth.assertThat
import io.element.android.watchbridge.contract.WatchCompanionSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WearCompanionSettingsActivityTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun `send test notification action is exposed and clickable`() {
        var invoked = false

        composeRule.runOnUiThread {
            Robolectric.buildActivity(ComponentActivity::class.java)
                .setup()
                .get()
                .setContent {
                    MaterialTheme {
                        WearCompanionSettingsScreen(
                            settings = WatchCompanionSettings(),
                            onUpdateSettings = {},
                            onSendTestNotification = { invoked = true },
                            onBack = {},
                            notificationStrings = WearCompanionNotificationSectionStrings(
                                sectionTitle = "Notification test",
                                actionTitle = "Send test notification",
                                actionDescription = "Mirror a diagnostic notification to your watch to verify notification forwarding.",
                            ),
                        )
                    }
                }
        }

        composeRule.onNodeWithTag(WEAR_COMPANION_SEND_TEST_NOTIFICATION_TAG)
            .performSemanticsAction(SemanticsActions.OnClick)

        composeRule.runOnIdle {
            assertThat(invoked).isTrue()
        }
    }
}