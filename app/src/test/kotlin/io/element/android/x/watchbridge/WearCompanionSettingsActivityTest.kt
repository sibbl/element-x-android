/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.watchbridge

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.element.android.compound.theme.ElementTheme
import io.element.android.watchbridge.contract.WatchCompanionSettings
import io.element.android.watchbridge.contract.WatchConversationVibrationOverride
import io.element.android.watchbridge.contract.WatchFavoriteRoom
import io.element.android.watchbridge.contract.WatchLongPressMessageAction
import io.element.android.watchbridge.contract.WatchNotificationVibrationPattern
import io.element.android.watchbridge.contract.WatchNotificationVibrationSettings
import io.element.android.watchbridge.contract.WatchRoomKind
import io.element.android.watchbridge.contract.WatchTileConversationAction
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

    private val appContext = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `send test notification action is exposed per category and clickable`() {
        var invokedCategory: WearCompanionVibrationCategory? = null

        setWearContent(
            onSendTestNotification = { invokedCategory = it },
        )

        composeRule.onNodeWithTag(wearCompanionTestNotificationTag(WearCompanionVibrationCategory.FAVORITE_DMS))
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle {
            assertThat(invokedCategory).isEqualTo(WearCompanionVibrationCategory.FAVORITE_DMS)
        }
    }

    @Test
    fun `default vibration pattern helper updates settings`() {
        val updatedSettings = WatchCompanionSettings().withNotificationVibration(
            category = WearCompanionVibrationCategory.FAVORITE_DMS,
            pattern = WatchNotificationVibrationPattern.ESCALATING,
        )

        assertThat(updatedSettings.notificationVibrations.favoriteDms)
            .isEqualTo(WatchNotificationVibrationPattern.ESCALATING)
    }

    @Test
    fun `message long press action can be updated`() {
        val updatedSettings = WatchCompanionSettings().copy(
            longPressMessageAction = WatchLongPressMessageAction.REPLY_VOICE,
        )

        assertThat(updatedSettings.longPressMessageAction)
            .isEqualTo(WatchLongPressMessageAction.REPLY_VOICE)
    }

    @Test
    fun `recent tile action can be updated`() {
        val updatedSettings = WatchCompanionSettings().copy(
            recentConversationsTileAction = WatchTileConversationAction.QUICK_REPLY_VOICE,
        )

        assertThat(updatedSettings.recentConversationsTileAction)
            .isEqualTo(WatchTileConversationAction.QUICK_REPLY_VOICE)
    }

    @Test
    fun `conversation selection helper adds selected room`() {
        val roomId = "!dm:server"

        val updatedSettings = WatchCompanionSettings().withConversationSelection(roomId)

        assertThat(updatedSettings.notificationVibrations.conversationOverrides)
            .containsExactly(WatchConversationVibrationOverride(roomId = roomId))
    }

    @Test
    fun `custom conversation vibration helper stores pattern and waveform`() {
        val roomId = "!room:server"
        val initialSettings = WatchCompanionSettings(
            notificationVibrations = WatchNotificationVibrationSettings(
                conversationOverrides = listOf(
                    WatchConversationVibrationOverride(roomId = roomId),
                ),
            ),
        )

        val updatedSettings = initialSettings.withConversationNotificationVibration(
            roomId = roomId,
            pattern = WatchNotificationVibrationPattern.TRIPLE,
        )

        assertThat(updatedSettings.notificationVibrations.conversationOverrides)
            .containsExactly(
                WatchConversationVibrationOverride(
                    roomId = roomId,
                    pattern = WatchNotificationVibrationPattern.TRIPLE,
                ),
            )
    }

    @Test
    fun `conversation vibration helper can reset to inherit`() {
        val roomId = "!room:server"
        val initialSettings = WatchCompanionSettings(
            notificationVibrations = WatchNotificationVibrationSettings(
                conversationOverrides = listOf(
                    WatchConversationVibrationOverride(
                        roomId = roomId,
                        pattern = WatchNotificationVibrationPattern.DOUBLE,
                    ),
                ),
            ),
        )

        val updatedSettings = initialSettings.withConversationNotificationVibration(
            roomId = roomId,
            pattern = null,
        )

        assertThat(updatedSettings.notificationVibrations.conversationOverrides)
            .containsExactly(WatchConversationVibrationOverride(roomId = roomId))
    }

    @Test
    fun `conversation row shows only localized selected pattern and accessible actions`() {
        val room = aRoom(roomId = "!room:server", displayName = "Team Wear", kind = WatchRoomKind.GROUP)
        setWearContent(
            initialSettings = WatchCompanionSettings(
                notificationVibrations = WatchNotificationVibrationSettings(
                    conversationOverrides = listOf(
                        WatchConversationVibrationOverride(room.roomId, WatchNotificationVibrationPattern.CUSTOM, "0,120"),
                    ),
                ),
            ),
            availableRooms = listOf(room),
        )

        composeRule.onNodeWithText("Custom").performScrollTo().assertExists()
        composeRule.onNodeWithText("Custom vibration pattern (e.g., \"100, 100, 200\")").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Test the selected vibration pattern for this conversation.").assertExists()
        composeRule.onNodeWithContentDescription("Delete this conversation-specific vibration behavior.").assertExists()
    }

    @Test
    fun `conversation test button sends the configured custom pattern`() {
        val room = aRoom(roomId = "!room:server", displayName = "Team Wear", kind = WatchRoomKind.GROUP)
        var tested: Triple<WatchFavoriteRoom, WatchNotificationVibrationPattern, String>? = null
        setWearContent(
            initialSettings = WatchCompanionSettings(
                notificationVibrations = WatchNotificationVibrationSettings(
                    conversationOverrides = listOf(
                        WatchConversationVibrationOverride(room.roomId, WatchNotificationVibrationPattern.CUSTOM, "0,120,80,240"),
                    ),
                ),
            ),
            availableRooms = listOf(room),
            onSendConversationPatternTest = { testedRoom, pattern, custom -> tested = Triple(testedRoom, pattern, custom) },
        )

        composeRule.onNodeWithTag(wearCompanionConversationTestTag(room.roomId)).performScrollTo().performClick()

        composeRule.runOnIdle {
            assertThat(tested).isEqualTo(Triple(room, WatchNotificationVibrationPattern.CUSTOM, "0,120,80,240"))
        }
    }

    @Test
    fun `remove conversation button deletes the custom behavior`() {
        var updatedSettings: WatchCompanionSettings? = null
        val room = aRoom(roomId = "!room:server", displayName = "Team Wear", kind = WatchRoomKind.GROUP)
        val initialSettings = WatchCompanionSettings(
            notificationVibrations = WatchNotificationVibrationSettings(
                conversationOverrides = listOf(
                    WatchConversationVibrationOverride(
                        roomId = room.roomId,
                        pattern = WatchNotificationVibrationPattern.ESCALATING,
                    ),
                ),
            ),
        )

        setWearContent(
            initialSettings = initialSettings,
            availableRooms = listOf(room),
            onSettingsChanged = { updatedSettings = it },
        )

        composeRule.onNodeWithTag(wearCompanionConversationRemoveTag(room.roomId)).performScrollTo().performClick()

        composeRule.runOnIdle {
            assertThat(updatedSettings?.notificationVibrations?.conversationOverrides).isEmpty()
        }
        composeRule.onNodeWithTag(wearCompanionConversationVibrationSelectorTag(room.roomId)).assertDoesNotExist()
    }

    private fun setWearContent(
        initialSettings: WatchCompanionSettings = WatchCompanionSettings(),
        availableRooms: List<WatchFavoriteRoom> = emptyList(),
        onSettingsChanged: (WatchCompanionSettings) -> Unit = {},
        onSendTestNotification: (WearCompanionVibrationCategory) -> Unit = {},
        onSendConversationPatternTest: (WatchFavoriteRoom, WatchNotificationVibrationPattern, String) -> Unit = { _, _, _ -> },
    ) {
        composeRule.runOnUiThread {
            Robolectric.buildActivity(ComponentActivity::class.java)
                .setup()
                .get()
                .setContent {
                    ElementTheme(applySystemBarsUpdate = false) {
                        var currentSettings by remember { mutableStateOf(initialSettings) }
                        WearCompanionSettingsScreen(
                            settings = currentSettings,
                            availableRooms = availableRooms,
                            onUpdateSettings = {
                                currentSettings = it
                                onSettingsChanged(it)
                            },
                            onSendTestNotification = onSendTestNotification,
                            onSendConversationPatternTest = onSendConversationPatternTest,
                            onBack = {},
                            usePreferencePage = false,
                            usePlatformDialogs = false,
                            screenTitle = "Wear OS companion",
                            commonStrings = aCommonStrings(),
                            longPressStrings = aLongPressSectionStrings(),
                            notificationStrings = aNotificationSectionStrings(),
                            vibrationStrings = aVibrationSectionStrings(),
                            tileActionStrings = aTileActionSectionStrings(),
                        )
                    }
                }
        }
    }

    private fun aRoom(
        roomId: String,
        displayName: String,
        kind: WatchRoomKind,
        isFavorite: Boolean = false,
    ): WatchFavoriteRoom {
        return WatchFavoriteRoom(
            roomId = roomId,
            displayName = displayName,
            kind = kind,
            isFavorite = isFavorite,
        )
    }

    private fun aLongPressSectionStrings(): WearCompanionLongPressSectionStrings {
        return WearCompanionLongPressSectionStrings(
            sectionTitle = "Long press behavior in conversation list",
            messagesLabel = "Messages",
            conversationsLabel = "Conversations",
        )
    }

    private fun aCommonStrings(): WearCompanionCommonStrings {
        return WearCompanionCommonStrings(
            saveLabel = "Save",
            cancelLabel = "Cancel",
            searchLabel = "Search",
        )
    }

    private fun aNotificationSectionStrings(): WearCompanionNotificationSectionStrings {
        return WearCompanionNotificationSectionStrings(
            sectionTitle = "Notification test",
            actionDescriptionFormat = "Send a realistic sample notification using %1\$s vibration.",
        )
    }

    private fun aVibrationSectionStrings(): WearCompanionVibrationSectionStrings {
        return WearCompanionVibrationSectionStrings(
            sectionTitle = "Vibration patterns",
            conversationOverridesSectionTitle = "Conversation-specific vibration patterns",
            conversationOverridesEmpty = "No conversations added yet.",
            conversationPickerNoRooms = "Open the main app first to load conversations.",
            conversationPickerEmpty = "No conversations match your search.",
            conversationPickerAllAdded = "All loaded conversations already have custom behavior.",
            conversationPickerTitle = "Choose conversation",
            addConversationLabel = "Add conversation",
            addConversationDescription = "Select a conversation and configure its own vibration behavior.",
            conversationRemoveLabel = "Remove",
            conversationRemoveDescription = "Delete this conversation-specific vibration behavior.",
            conversationTestLabel = "Test",
            conversationTestDescription = "Test the selected vibration pattern for this conversation.",
            inheritLabel = "Use category default",
            inheritDescription = "Follow the default vibration for this conversation type.",
            groupsLabel = "Group conversations",
            dmsLabel = "Direct messages",
            favoriteGroupsLabel = "Favorite group conversations",
            favoriteDmsLabel = "Favorite direct messages",
            silentLabel = "Silent",
            silentDescription = "No vibration",
            defaultLabel = "Default",
            defaultDescription = "System default vibration",
            doubleLabel = "Double",
            doubleDescription = "Two short buzzes",
            longLabel = "Long",
            longDescription = "One long buzz",
            tripleLabel = "Triple",
            tripleDescription = "Three short buzzes",
            pulseLabel = "Pulse",
            pulseDescription = "Four even pulses",
            escalatingLabel = "Escalating",
            escalatingDescription = "Short buzzes that ramp up in strength",
            customLabel = "Custom",
            customDescription = "Custom vibration pattern (e.g., \"100, 100, 200\")",
            customPatternDialogTitle = "Custom vibration pattern",
            customPatternFieldLabel = "Pattern",
            customPatternDescription = "Enter timing in ms (e.g., \"100, 100, 200\" for dot-dot-dash)",
            customPatternError = "Invalid pattern. Use comma-separated numbers.",
            customPatternTestLabel = "Test vibration",
            customPatternTestDescription = "Try the pattern on your watch",
        )
    }

    private fun aTileActionSectionStrings(): WearCompanionTileActionSectionStrings {
        return WearCompanionTileActionSectionStrings(
            sectionTitle = "Tile conversation buttons",
            recentTileLabel = "Recent conversations tile",
            favoriteTileLabel = "Favorite conversations tile",
            openConversationLabel = "Open conversation",
            readLatestLabel = "Read latest message",
            quickReplyEmojiLabel = "Quick reply with emoji",
            quickReplyTextLabel = "Quick reply with text",
            quickReplyVoiceLabel = "Quick reply with voice message",
            openLatestLabel = "Open latest message",
        )
    }
}
