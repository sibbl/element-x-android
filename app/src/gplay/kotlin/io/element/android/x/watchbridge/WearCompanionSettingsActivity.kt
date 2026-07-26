/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.watchbridge

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.components.dialogs.ListDialog
import io.element.android.libraries.designsystem.components.list.ListItemContent
import io.element.android.libraries.designsystem.components.list.TextFieldListItem
import io.element.android.libraries.designsystem.components.preferences.PreferenceCategory
import io.element.android.libraries.designsystem.components.preferences.PreferenceDivider
import io.element.android.libraries.designsystem.components.preferences.PreferencePage
import io.element.android.libraries.designsystem.theme.components.ListItem
import io.element.android.libraries.designsystem.theme.components.ListItemStyle
import io.element.android.libraries.designsystem.theme.components.TextButton
import io.element.android.libraries.designsystem.theme.components.ButtonSize
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.watchbridge.contract.WatchCompanionSettings
import io.element.android.watchbridge.contract.WatchConversationVibrationOverride
import io.element.android.watchbridge.contract.WatchFavoriteRoom
import io.element.android.watchbridge.contract.WatchLongPressConversationAction
import io.element.android.watchbridge.contract.WatchLongPressMessageAction
import io.element.android.watchbridge.contract.WatchNotificationVibrationPattern
import io.element.android.watchbridge.contract.WatchNotificationVibrationSettings
import io.element.android.watchbridge.contract.WatchTileConversationAction
import io.element.android.watchbridge.contract.parseCustomWatchNotificationVibrationPattern
import io.element.android.x.R
import kotlinx.coroutines.launch

internal const val WEAR_COMPANION_SEND_TEST_NOTIFICATION_TAG_PREFIX = "wear-companion-send-test-notification"
internal const val WEAR_COMPANION_VIBRATION_SELECTOR_TAG_PREFIX = "wear-companion-vibration-selector"
internal const val WEAR_COMPANION_VIBRATION_OPTION_TAG_PREFIX = "wear-companion-vibration-option"
internal const val WEAR_COMPANION_MESSAGE_ACTION_SELECTOR_TAG = "wear-companion-message-action-selector"
internal const val WEAR_COMPANION_MESSAGE_ACTION_OPTION_TAG_PREFIX = "wear-companion-message-action-option"
internal const val WEAR_COMPANION_CONVERSATION_ACTION_SELECTOR_TAG = "wear-companion-conversation-action-selector"
internal const val WEAR_COMPANION_CONVERSATION_ACTION_OPTION_TAG_PREFIX = "wear-companion-conversation-action-option"
internal const val WEAR_COMPANION_RECENT_TILE_ACTION_SELECTOR_TAG = "wear-companion-recent-tile-action-selector"
internal const val WEAR_COMPANION_FAVORITE_TILE_ACTION_SELECTOR_TAG = "wear-companion-favorite-tile-action-selector"
internal const val WEAR_COMPANION_TILE_ACTION_OPTION_TAG_PREFIX = "wear-companion-tile-action-option"
internal const val WEAR_COMPANION_DIALOG_CANCEL_TAG = "wear-companion-dialog-cancel"
internal const val WEAR_COMPANION_DIALOG_SAVE_TAG = "wear-companion-dialog-save"
internal const val WEAR_COMPANION_CONVERSATION_VIBRATION_SELECTOR_TAG_PREFIX = "wear-companion-conversation-vibration-selector"
internal const val WEAR_COMPANION_CONVERSATION_VIBRATION_OPTION_TAG_PREFIX = "wear-companion-conversation-vibration-option"
internal const val WEAR_COMPANION_ADD_CONVERSATION_BUTTON_TAG = "wear-companion-add-conversation-button"
internal const val WEAR_COMPANION_ADD_CONVERSATION_SEARCH_TAG = "wear-companion-add-conversation-search"
internal const val WEAR_COMPANION_ADD_CONVERSATION_OPTION_TAG_PREFIX = "wear-companion-add-conversation-option"
internal const val WEAR_COMPANION_CONVERSATION_REMOVE_TAG_PREFIX = "wear-companion-conversation-remove"
internal const val WEAR_COMPANION_CONVERSATION_TEST_TAG_PREFIX = "wear-companion-conversation-test"
internal const val WEAR_COMPANION_CUSTOM_PATTERN_INPUT_TAG = "wear-companion-custom-pattern-input"
internal const val WEAR_COMPANION_CUSTOM_PATTERN_TEST_TAG = "wear-companion-custom-pattern-test"

internal enum class WearCompanionVibrationCategory {
    GROUPS,
    DMS,
    FAVORITE_GROUPS,
    FAVORITE_DMS,
}

internal enum class WearCompanionTileKind {
    RECENT,
    FAVORITE,
}

internal fun wearCompanionVibrationOptionTag(
    category: WearCompanionVibrationCategory,
    pattern: WatchNotificationVibrationPattern,
): String = "$WEAR_COMPANION_VIBRATION_OPTION_TAG_PREFIX:${category.name}:${pattern.name}"

internal fun wearCompanionVibrationSelectorTag(
    category: WearCompanionVibrationCategory,
): String = "$WEAR_COMPANION_VIBRATION_SELECTOR_TAG_PREFIX:${category.name}"

internal fun wearCompanionTestNotificationTag(
    category: WearCompanionVibrationCategory,
): String = "$WEAR_COMPANION_SEND_TEST_NOTIFICATION_TAG_PREFIX:${category.name}"

internal fun wearCompanionMessageActionOptionTag(
    action: WatchLongPressMessageAction,
): String = "$WEAR_COMPANION_MESSAGE_ACTION_OPTION_TAG_PREFIX:${action.name}"

internal fun wearCompanionConversationActionOptionTag(
    action: WatchLongPressConversationAction,
): String = "$WEAR_COMPANION_CONVERSATION_ACTION_OPTION_TAG_PREFIX:${action.name}"

internal fun wearCompanionTileActionOptionTag(
    kind: WearCompanionTileKind,
    action: WatchTileConversationAction,
): String = "$WEAR_COMPANION_TILE_ACTION_OPTION_TAG_PREFIX:${kind.name}:${action.name}"

internal fun wearCompanionConversationVibrationSelectorTag(roomId: String): String {
    return "$WEAR_COMPANION_CONVERSATION_VIBRATION_SELECTOR_TAG_PREFIX:${roomId.asTagComponent()}"
}

internal fun wearCompanionConversationVibrationOptionTag(
    roomId: String,
    pattern: WatchNotificationVibrationPattern?,
): String {
    val option = pattern?.name ?: "INHERIT"
    return "$WEAR_COMPANION_CONVERSATION_VIBRATION_OPTION_TAG_PREFIX:${roomId.asTagComponent()}:$option"
}

internal fun wearCompanionAddConversationOptionTag(roomId: String): String {
    return "$WEAR_COMPANION_ADD_CONVERSATION_OPTION_TAG_PREFIX:${roomId.asTagComponent()}"
}

internal fun wearCompanionConversationRemoveTag(roomId: String): String {
    return "$WEAR_COMPANION_CONVERSATION_REMOVE_TAG_PREFIX:${roomId.asTagComponent()}"
}

internal fun wearCompanionConversationTestTag(roomId: String): String {
    return "$WEAR_COMPANION_CONVERSATION_TEST_TAG_PREFIX:${roomId.asTagComponent()}"
}

private fun String.asTagComponent(): String = Uri.encode(this)

private sealed interface WearCompanionSettingsDialog {
    data object MessageActions : WearCompanionSettingsDialog
    data object ConversationActions : WearCompanionSettingsDialog
    data object RecentTileAction : WearCompanionSettingsDialog
    data object FavoriteTileAction : WearCompanionSettingsDialog
    data class Vibration(val category: WearCompanionVibrationCategory) : WearCompanionSettingsDialog
    data class CustomCategoryPattern(
        val category: WearCompanionVibrationCategory,
        val initialPattern: String,
    ) : WearCompanionSettingsDialog
    data class ConversationVibration(val roomId: String) : WearCompanionSettingsDialog
    data object AddConversation : WearCompanionSettingsDialog
    data class CustomConversationPattern(
        val roomId: String,
        val initialPattern: String,
    ) : WearCompanionSettingsDialog
}

private data class WearCompanionSelectorOption<T>(
    val value: T,
    val label: String,
    val description: String? = null,
    val tag: String,
)

private data class WearCompanionManagedConversation(
    val roomId: String,
    val displayName: String,
    val override: WatchConversationVibrationOverride,
)

internal data class WearCompanionLongPressSectionStrings(
    val sectionTitle: String,
    val messagesLabel: String,
    val conversationsLabel: String,
)

internal data class WearCompanionCommonStrings(
    val saveLabel: String,
    val cancelLabel: String,
    val searchLabel: String,
)

internal data class WearCompanionNotificationSectionStrings(
    val sectionTitle: String,
    val actionDescriptionFormat: String,
)

internal data class WearCompanionVibrationSectionStrings(
    val sectionTitle: String,
    val conversationOverridesSectionTitle: String,
    val conversationOverridesEmpty: String,
    val conversationPickerNoRooms: String,
    val conversationPickerEmpty: String,
    val conversationPickerAllAdded: String,
    val conversationPickerTitle: String,
    val addConversationLabel: String,
    val addConversationDescription: String,
    val conversationRemoveLabel: String,
    val conversationRemoveDescription: String,
    val conversationTestLabel: String,
    val conversationTestDescription: String,
    val inheritLabel: String,
    val inheritDescription: String,
    val groupsLabel: String,
    val dmsLabel: String,
    val favoriteGroupsLabel: String,
    val favoriteDmsLabel: String,
    val silentLabel: String,
    val silentDescription: String,
    val defaultLabel: String,
    val defaultDescription: String,
    val doubleLabel: String,
    val doubleDescription: String,
    val longLabel: String,
    val longDescription: String,
    val tripleLabel: String,
    val tripleDescription: String,
    val pulseLabel: String,
    val pulseDescription: String,
    val escalatingLabel: String,
    val escalatingDescription: String,
    val customLabel: String,
    val customDescription: String,
    val customPatternDialogTitle: String,
    val customPatternFieldLabel: String,
    val customPatternDescription: String,
    val customPatternError: String,
    val customPatternTestLabel: String,
    val customPatternTestDescription: String,
)

internal data class WearCompanionTileActionSectionStrings(
    val sectionTitle: String,
    val recentTileLabel: String,
    val favoriteTileLabel: String,
    val openConversationLabel: String,
    val readLatestLabel: String,
    val quickReplyEmojiLabel: String,
    val quickReplyTextLabel: String,
    val quickReplyVoiceLabel: String,
    val openLatestLabel: String,
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
        val visibleRooms = ElementXWatchBridgeRuntime.visibleWatchRooms(this)

        setContent {
            ElementTheme {
                val settings by store.settings.collectAsState()
                val availableRooms by visibleRooms.collectAsState(initial = emptyList())
                WearCompanionSettingsScreen(
                    settings = settings,
                    availableRooms = availableRooms,
                    onUpdateSettings = { store.update(it) },
                    onSendTestNotification = { category ->
                        lifecycleScope.launch {
                            notificationTester.send(category, settings)
                        }
                    },
                    onSendCategoryPatternTest = { category, customPattern ->
                        lifecycleScope.launch {
                            notificationTester.sendCategoryPatternTest(
                                category = category,
                                customPattern = customPattern,
                            )
                        }
                    },
                    onSendConversationPatternTest = { room, pattern, customPattern ->
                        lifecycleScope.launch {
                            notificationTester.sendConversationPatternTest(
                                room = room,
                                pattern = pattern,
                                customPattern = customPattern,
                            )
                        }
                    },
                    onBack = { finish() },
                )
            }
        }
    }
}

@Composable
internal fun WearCompanionSettingsScreen(
    settings: WatchCompanionSettings,
    availableRooms: List<WatchFavoriteRoom> = emptyList(),
    onUpdateSettings: (WatchCompanionSettings) -> Unit,
    onSendTestNotification: (WearCompanionVibrationCategory) -> Unit,
    onBack: () -> Unit,
    usePreferencePage: Boolean = true,
    usePlatformDialogs: Boolean = true,
    screenTitle: String? = null,
    commonStrings: WearCompanionCommonStrings? = null,
    longPressStrings: WearCompanionLongPressSectionStrings? = null,
    notificationStrings: WearCompanionNotificationSectionStrings? = null,
    vibrationStrings: WearCompanionVibrationSectionStrings? = null,
    tileActionStrings: WearCompanionTileActionSectionStrings? = null,
    onSendCategoryPatternTest: (WearCompanionVibrationCategory, String) -> Unit = { _, _ -> },
    onSendConversationPatternTest: (WatchFavoriteRoom, WatchNotificationVibrationPattern, String) -> Unit = { _, _, _ -> },
) {
    val resolvedScreenTitle = screenTitle ?: stringResource(R.string.screen_wear_companion_title)
    val resolvedCommonStrings = commonStrings ?: rememberWearCompanionCommonStrings()
    val resolvedLongPressStrings = longPressStrings ?: rememberWearCompanionLongPressSectionStrings()
    val resolvedNotificationStrings = notificationStrings ?: rememberWearCompanionNotificationSectionStrings()
    val resolvedVibrationStrings = vibrationStrings ?: rememberWearCompanionVibrationSectionStrings()
    val resolvedTileActionStrings = tileActionStrings ?: rememberWearCompanionTileActionSectionStrings()
    val knownRooms = remember(availableRooms) { availableRooms.distinctBy { it.roomId } }
    val knownRoomsById = remember(knownRooms) { knownRooms.associateBy { it.roomId } }
    val managedConversations = remember(settings.notificationVibrations.conversationOverrides, knownRoomsById) {
        settings.notificationVibrations.conversationOverrides
            .distinctBy { it.roomId }
            .map { override ->
                WearCompanionManagedConversation(
                    roomId = override.roomId,
                    displayName = knownRoomsById[override.roomId]?.displayNameOrRoomId() ?: override.roomId,
                    override = override,
                )
            }
            .sortedBy { it.displayName.lowercase() }
    }
    val addableRooms = remember(knownRooms, managedConversations) {
        val selectedRoomIds = managedConversations.mapTo(mutableSetOf()) { it.roomId }
        knownRooms
            .filterNot { it.roomId in selectedRoomIds }
            .sortedBy { it.displayNameOrRoomId().lowercase() }
    }
    var dialog by remember { mutableStateOf<WearCompanionSettingsDialog?>(null) }

    val sections: @Composable ColumnScope.() -> Unit = {
        PreferenceCategory(
            title = resolvedLongPressStrings.sectionTitle,
            showTopDivider = false,
        ) {
            WearCompanionSelectionRow(
                title = resolvedLongPressStrings.messagesLabel,
                selectedOption = settings.longPressMessageAction.toSelectorOption(),
                selectorTag = WEAR_COMPANION_MESSAGE_ACTION_SELECTOR_TAG,
                onClick = { dialog = WearCompanionSettingsDialog.MessageActions },
            )
            PreferenceDivider()
            WearCompanionSelectionRow(
                title = resolvedLongPressStrings.conversationsLabel,
                selectedOption = settings.longPressConversationAction.toSelectorOption(),
                selectorTag = WEAR_COMPANION_CONVERSATION_ACTION_SELECTOR_TAG,
                onClick = { dialog = WearCompanionSettingsDialog.ConversationActions },
            )
        }

        PreferenceCategory(title = resolvedTileActionStrings.sectionTitle) {
            WearCompanionSelectionRow(
                title = resolvedTileActionStrings.recentTileLabel,
                selectedOption = settings.recentConversationsTileAction.normalizedTileAction().toSelectorOption(
                    kind = WearCompanionTileKind.RECENT,
                    strings = resolvedTileActionStrings,
                ),
                selectorTag = WEAR_COMPANION_RECENT_TILE_ACTION_SELECTOR_TAG,
                onClick = { dialog = WearCompanionSettingsDialog.RecentTileAction },
            )
            PreferenceDivider()
            WearCompanionSelectionRow(
                title = resolvedTileActionStrings.favoriteTileLabel,
                selectedOption = settings.favoriteConversationsTileAction.normalizedTileAction().toSelectorOption(
                    kind = WearCompanionTileKind.FAVORITE,
                    strings = resolvedTileActionStrings,
                ),
                selectorTag = WEAR_COMPANION_FAVORITE_TILE_ACTION_SELECTOR_TAG,
                onClick = { dialog = WearCompanionSettingsDialog.FavoriteTileAction },
            )
        }

        PreferenceCategory(title = resolvedVibrationStrings.sectionTitle) {
            WearCompanionVibrationCategory.entries.forEachIndexed { index, category ->
                if (index > 0) {
                    PreferenceDivider()
                }
                WearCompanionSelectionRow(
                    title = category.displayLabel(resolvedVibrationStrings),
                    selectedOption = settings.notificationVibrationOptionFor(category, resolvedVibrationStrings),
                    selectorTag = wearCompanionVibrationSelectorTag(category),
                    onClick = { dialog = WearCompanionSettingsDialog.Vibration(category) },
                )
            }
        }

        PreferenceCategory(title = resolvedVibrationStrings.conversationOverridesSectionTitle) {
            val addConversationDescription = when {
                knownRooms.isEmpty() -> resolvedVibrationStrings.conversationPickerNoRooms
                addableRooms.isEmpty() -> resolvedVibrationStrings.conversationPickerAllAdded
                else -> resolvedVibrationStrings.addConversationDescription
            }
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(WEAR_COMPANION_ADD_CONVERSATION_BUTTON_TAG),
                headlineContent = { Text(resolvedVibrationStrings.addConversationLabel) },
                supportingContent = { Text(addConversationDescription) },
                style = ListItemStyle.Primary,
                enabled = addableRooms.isNotEmpty(),
                onClick = { dialog = WearCompanionSettingsDialog.AddConversation },
            )
            PreferenceDivider()
            if (managedConversations.isEmpty()) {
                ListItem(
                    modifier = Modifier.fillMaxWidth(),
                    headlineContent = { Text(resolvedVibrationStrings.conversationOverridesEmpty) },
                    enabled = false,
                )
            } else {
                managedConversations.forEachIndexed { index, conversation ->
                    WearCompanionSelectionRow(
                        title = conversation.displayName,
                        selectedOption = settings.conversationVibrationOptionFor(
                            roomId = conversation.roomId,
                            strings = resolvedVibrationStrings,
                        ),
                        selectorTag = wearCompanionConversationVibrationSelectorTag(conversation.roomId),
                        onClick = {
                            dialog = WearCompanionSettingsDialog.ConversationVibration(conversation.roomId)
                        },
                        actions = listOf(
                            WearCompanionRowAction(
                                label = resolvedVibrationStrings.conversationTestLabel,
                                contentDescription = resolvedVibrationStrings.conversationTestDescription,
                                tag = wearCompanionConversationTestTag(conversation.roomId),
                                onClick = {
                                    knownRoomsById[conversation.roomId]?.let { room ->
                                        val pattern = conversation.override.pattern ?: settings.notificationVibrationFor(room.toVibrationCategory())
                                        val customPattern = if (conversation.override.pattern == WatchNotificationVibrationPattern.CUSTOM) {
                                            conversation.override.customPattern
                                        } else if (conversation.override.pattern == null && pattern == WatchNotificationVibrationPattern.CUSTOM) {
                                            settings.notificationCustomPatternFor(room.toVibrationCategory())
                                        } else ""
                                        onSendConversationPatternTest(room, pattern, customPattern)
                                    }
                                },
                            ),
                            WearCompanionRowAction(
                                label = resolvedVibrationStrings.conversationRemoveLabel,
                                contentDescription = resolvedVibrationStrings.conversationRemoveDescription,
                                tag = wearCompanionConversationRemoveTag(conversation.roomId),
                                critical = true,
                                onClick = {
                                    onUpdateSettings(settings.withoutConversationNotificationVibration(conversation.roomId))
                                },
                            ),
                        ),
                    )
                    if (index < managedConversations.lastIndex) {
                        PreferenceDivider()
                    }
                }
            }
        }

        PreferenceCategory(title = resolvedNotificationStrings.sectionTitle) {
            WearCompanionVibrationCategory.entries.forEachIndexed { index, category ->
                if (index > 0) {
                    PreferenceDivider()
                }
                val selectedPattern = settings.notificationVibrationFor(category)
                ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(wearCompanionTestNotificationTag(category)),
                    headlineContent = { Text(category.displayLabel(resolvedVibrationStrings)) },
                    supportingContent = {
                        Text(
                            resolvedNotificationStrings.actionDescriptionFormat.format(
                                selectedPattern.displayLabel(resolvedVibrationStrings),
                            ),
                        )
                    },
                    onClick = { onSendTestNotification(category) },
                )
            }
        }
    }

    val dialogContent: @Composable () -> Unit = {
        when (val currentDialog = dialog) {
            WearCompanionSettingsDialog.MessageActions -> {
                WearCompanionSettingsSelectionDialog(
                    title = resolvedLongPressStrings.messagesLabel,
                    options = WatchLongPressMessageAction.entries.map { it.toSelectorOption() },
                    initialSelection = settings.longPressMessageAction,
                    commonStrings = resolvedCommonStrings,
                    usePlatformDialog = usePlatformDialogs,
                    onDismissRequest = { dialog = null },
                    onSubmit = { action ->
                        onUpdateSettings(settings.copy(longPressMessageAction = action))
                        dialog = null
                    },
                )
            }
            WearCompanionSettingsDialog.ConversationActions -> {
                WearCompanionSettingsSelectionDialog(
                    title = resolvedLongPressStrings.conversationsLabel,
                    options = WatchLongPressConversationAction.entries.map { it.toSelectorOption() },
                    initialSelection = settings.longPressConversationAction,
                    commonStrings = resolvedCommonStrings,
                    usePlatformDialog = usePlatformDialogs,
                    onDismissRequest = { dialog = null },
                    onSubmit = { action ->
                        onUpdateSettings(settings.copy(longPressConversationAction = action))
                        dialog = null
                    },
                )
            }
            WearCompanionSettingsDialog.RecentTileAction -> {
                WearCompanionSettingsSelectionDialog(
                    title = resolvedTileActionStrings.recentTileLabel,
                    options = tileConversationActionOptions.map { action ->
                        action.toSelectorOption(
                            kind = WearCompanionTileKind.RECENT,
                            strings = resolvedTileActionStrings,
                        )
                    },
                    initialSelection = settings.recentConversationsTileAction.normalizedTileAction(),
                    commonStrings = resolvedCommonStrings,
                    usePlatformDialog = usePlatformDialogs,
                    onDismissRequest = { dialog = null },
                    onSubmit = { action ->
                        onUpdateSettings(settings.copy(recentConversationsTileAction = action))
                        dialog = null
                    },
                )
            }
            WearCompanionSettingsDialog.FavoriteTileAction -> {
                WearCompanionSettingsSelectionDialog(
                    title = resolvedTileActionStrings.favoriteTileLabel,
                    options = tileConversationActionOptions.map { action ->
                        action.toSelectorOption(
                            kind = WearCompanionTileKind.FAVORITE,
                            strings = resolvedTileActionStrings,
                        )
                    },
                    initialSelection = settings.favoriteConversationsTileAction.normalizedTileAction(),
                    commonStrings = resolvedCommonStrings,
                    usePlatformDialog = usePlatformDialogs,
                    onDismissRequest = { dialog = null },
                    onSubmit = { action ->
                        onUpdateSettings(settings.copy(favoriteConversationsTileAction = action))
                        dialog = null
                    },
                )
            }
            is WearCompanionSettingsDialog.Vibration -> {
                WearCompanionSettingsSelectionDialog(
                    title = currentDialog.category.displayLabel(resolvedVibrationStrings),
                    options = categorySelectableVibrationPatterns().map { pattern ->
                        pattern.toSelectorOption(currentDialog.category, resolvedVibrationStrings)
                    },
                    initialSelection = settings.notificationVibrationFor(currentDialog.category),
                    commonStrings = resolvedCommonStrings,
                    usePlatformDialog = usePlatformDialogs,
                    onDismissRequest = { dialog = null },
                    onSubmit = { pattern ->
                        if (pattern == WatchNotificationVibrationPattern.CUSTOM) {
                            dialog = WearCompanionSettingsDialog.CustomCategoryPattern(
                                category = currentDialog.category,
                                initialPattern = settings.notificationCustomPatternFor(currentDialog.category),
                            )
                        } else {
                            onUpdateSettings(settings.withNotificationVibration(currentDialog.category, pattern))
                            dialog = null
                        }
                    },
                )
            }
            is WearCompanionSettingsDialog.CustomCategoryPattern -> {
                WearCompanionCustomPatternDialog(
                    roomTitle = currentDialog.category.displayLabel(resolvedVibrationStrings),
                    commonStrings = resolvedCommonStrings,
                    usePlatformDialog = usePlatformDialogs,
                    strings = resolvedVibrationStrings,
                    initialPattern = currentDialog.initialPattern,
                    canSendTest = true,
                    onDismissRequest = { dialog = null },
                    onSubmit = { customPattern ->
                        onUpdateSettings(
                            settings.withNotificationVibration(
                                category = currentDialog.category,
                                pattern = WatchNotificationVibrationPattern.CUSTOM,
                                customPattern = customPattern,
                            ),
                        )
                        dialog = null
                    },
                    onSendTest = { customPattern ->
                        onSendCategoryPatternTest(currentDialog.category, customPattern)
                    },
                )
            }
            is WearCompanionSettingsDialog.ConversationVibration -> {
                val roomName = knownRoomsById[currentDialog.roomId]?.displayNameOrRoomId() ?: currentDialog.roomId
                val currentOverride = settings.notificationVibrations.conversationOverrideFor(currentDialog.roomId)
                WearCompanionSettingsSelectionDialog(
                    title = roomName,
                    options = conversationVibrationOptions(currentDialog.roomId, resolvedVibrationStrings),
                    initialSelection = currentOverride?.pattern,
                    commonStrings = resolvedCommonStrings,
                    usePlatformDialog = usePlatformDialogs,
                    onDismissRequest = { dialog = null },
                    onSubmit = { pattern ->
                        if (pattern == WatchNotificationVibrationPattern.CUSTOM) {
                            dialog = WearCompanionSettingsDialog.CustomConversationPattern(
                                roomId = currentDialog.roomId,
                                initialPattern = currentOverride?.customPattern.orEmpty(),
                            )
                        } else {
                            onUpdateSettings(
                                settings.withConversationNotificationVibration(
                                    roomId = currentDialog.roomId,
                                    pattern = pattern,
                                ),
                            )
                            dialog = null
                        }
                    },
                )
            }
            is WearCompanionSettingsDialog.CustomConversationPattern -> {
                val room = knownRoomsById[currentDialog.roomId]
                WearCompanionCustomPatternDialog(
                    roomTitle = room?.displayNameOrRoomId() ?: currentDialog.roomId,
                    commonStrings = resolvedCommonStrings,
                    usePlatformDialog = usePlatformDialogs,
                    strings = resolvedVibrationStrings,
                    initialPattern = currentDialog.initialPattern,
                    canSendTest = room != null,
                    onDismissRequest = { dialog = null },
                    onSubmit = { customPattern ->
                        onUpdateSettings(
                            settings.withConversationNotificationVibration(
                                roomId = currentDialog.roomId,
                                pattern = WatchNotificationVibrationPattern.CUSTOM,
                                customPattern = customPattern,
                            ),
                        )
                        dialog = null
                    },
                    onSendTest = { customPattern ->
                        room?.let { knownRoom ->
                            onSendConversationPatternTest(knownRoom, WatchNotificationVibrationPattern.CUSTOM, customPattern)
                        }
                    },
                )
            }
            WearCompanionSettingsDialog.AddConversation -> {
                WearCompanionAddConversationDialog(
                    rooms = addableRooms,
                    commonStrings = resolvedCommonStrings,
                    usePlatformDialog = usePlatformDialogs,
                    strings = resolvedVibrationStrings,
                    onDismissRequest = { dialog = null },
                    onSubmit = { room ->
                        onUpdateSettings(settings.withConversationSelection(room.roomId))
                        dialog = null
                    },
                )
            }
            null -> Unit
        }
    }

    if (!usePlatformDialogs) {
        dialogContent()
    }

    if (usePreferencePage) {
        PreferencePage(
            title = resolvedScreenTitle,
            onBackClick = onBack,
            content = sections,
        )
    } else {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            content = sections,
        )
    }

    if (usePlatformDialogs) {
        dialogContent()
    }
}

private data class WearCompanionRowAction(
    val label: String,
    val contentDescription: String,
    val tag: String,
    val critical: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
private fun WearCompanionSelectionRow(
    title: String,
    selectedOption: WearCompanionSelectorOption<*>,
    selectorTag: String,
    onClick: () -> Unit,
    actions: List<WearCompanionRowAction> = emptyList(),
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(selectorTag),
        headlineContent = { Text(title) },
        supportingContent = if (actions.isEmpty()) {
            selectedOption.description?.let { description -> { Text(description) } }
        } else {
            { Text(selectedOption.label) }
        },
        trailingContent = if (actions.isEmpty()) {
            ListItemContent.Text(selectedOption.label)
        } else {
            ListItemContent.Custom {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    actions.forEach { action ->
                        TextButton(
                            text = action.label,
                            onClick = action.onClick,
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .testTag(action.tag)
                                .semantics { contentDescription = action.contentDescription },
                            size = ButtonSize.Small,
                            destructive = action.critical,
                        )
                    }
                }
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun <T> WearCompanionSettingsSelectionDialog(
    title: String,
    options: List<WearCompanionSelectorOption<T>>,
    initialSelection: T,
    commonStrings: WearCompanionCommonStrings,
    usePlatformDialog: Boolean,
    onDismissRequest: () -> Unit,
    onSubmit: (T) -> Unit,
    subtitle: String? = null,
) {
    var selectedValue by remember(initialSelection) { mutableStateOf(initialSelection) }

    if (usePlatformDialog) {
        ListDialog(
            title = title,
            subtitle = subtitle,
            onDismissRequest = onDismissRequest,
            onSubmit = { onSubmit(selectedValue) },
            cancelText = commonStrings.cancelLabel,
            submitText = commonStrings.saveLabel,
            applyPaddingToContents = false,
            verticalArrangement = Arrangement.spacedBy(0.dp),
            listItems = {
                options.forEachIndexed { index, option ->
                    item(key = option.tag) {
                        ListItem(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(option.tag),
                            headlineContent = { Text(option.label) },
                            supportingContent = option.description?.let { description ->
                                { Text(description) }
                            },
                            trailingContent = ListItemContent.RadioButton(
                                selected = selectedValue == option.value,
                                compact = true,
                            ),
                            onClick = { selectedValue = option.value },
                        )
                    }
                    if (index < options.lastIndex) {
                        item { PreferenceDivider() }
                    }
                }
            },
        )
    } else {
        Column {
            options.forEachIndexed { index, option ->
                ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(option.tag),
                    headlineContent = { Text(option.label) },
                    supportingContent = option.description?.let { description ->
                        { Text(description) }
                    },
                    trailingContent = ListItemContent.RadioButton(
                        selected = selectedValue == option.value,
                        compact = true,
                    ),
                    onClick = { selectedValue = option.value },
                )
                if (index < options.lastIndex) {
                    PreferenceDivider()
                }
            }
            PreferenceDivider()
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(WEAR_COMPANION_DIALOG_CANCEL_TAG),
                headlineContent = { Text(commonStrings.cancelLabel) },
                onClick = onDismissRequest,
            )
            PreferenceDivider()
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(WEAR_COMPANION_DIALOG_SAVE_TAG),
                headlineContent = { Text(commonStrings.saveLabel) },
                onClick = { onSubmit(selectedValue) },
            )
        }
    }
}

@Composable
private fun WearCompanionAddConversationDialog(
    rooms: List<WatchFavoriteRoom>,
    commonStrings: WearCompanionCommonStrings,
    usePlatformDialog: Boolean,
    strings: WearCompanionVibrationSectionStrings,
    onDismissRequest: () -> Unit,
    onSubmit: (WatchFavoriteRoom) -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedRoomId by remember { mutableStateOf<String?>(null) }
    val filteredRooms = remember(rooms, searchQuery) {
        rooms.filter { it.matchesSearch(searchQuery) }
    }

    if (usePlatformDialog) {
        ListDialog(
            title = strings.conversationPickerTitle,
            onDismissRequest = onDismissRequest,
            onSubmit = {
                val selectedRoom = rooms.firstOrNull { it.roomId == selectedRoomId } ?: return@ListDialog
                onSubmit(selectedRoom)
            },
            cancelText = commonStrings.cancelLabel,
            submitText = commonStrings.saveLabel,
            enabled = selectedRoomId != null,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            listItems = {
                item {
                    TextFieldListItem(
                        placeholder = commonStrings.searchLabel,
                        text = searchQuery,
                        onTextChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(WEAR_COMPANION_ADD_CONVERSATION_SEARCH_TAG),
                    )
                }
                if (rooms.isEmpty()) {
                    item {
                        ListItem(
                            modifier = Modifier.fillMaxWidth(),
                            headlineContent = { Text(strings.conversationPickerNoRooms) },
                            enabled = false,
                        )
                    }
                } else if (filteredRooms.isEmpty()) {
                    item {
                        ListItem(
                            modifier = Modifier.fillMaxWidth(),
                            headlineContent = { Text(strings.conversationPickerEmpty) },
                            enabled = false,
                        )
                    }
                } else {
                    items(
                        items = filteredRooms,
                        key = { it.roomId },
                    ) { room ->
                        ListItem(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(wearCompanionAddConversationOptionTag(room.roomId)),
                            headlineContent = { Text(room.displayNameOrRoomId()) },
                            trailingContent = ListItemContent.RadioButton(
                                selected = selectedRoomId == room.roomId,
                                compact = true,
                            ),
                            onClick = { selectedRoomId = room.roomId },
                        )
                    }
                }
            },
        )
    } else {
        Column {
            TextFieldListItem(
                placeholder = commonStrings.searchLabel,
                text = searchQuery,
                onTextChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(WEAR_COMPANION_ADD_CONVERSATION_SEARCH_TAG),
            )
            PreferenceDivider()
            if (rooms.isEmpty()) {
                ListItem(
                    modifier = Modifier.fillMaxWidth(),
                    headlineContent = { Text(strings.conversationPickerNoRooms) },
                    enabled = false,
                )
            } else if (filteredRooms.isEmpty()) {
                ListItem(
                    modifier = Modifier.fillMaxWidth(),
                    headlineContent = { Text(strings.conversationPickerEmpty) },
                    enabled = false,
                )
            } else {
                filteredRooms.forEachIndexed { index, room ->
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(wearCompanionAddConversationOptionTag(room.roomId)),
                        headlineContent = { Text(room.displayNameOrRoomId()) },
                        trailingContent = ListItemContent.RadioButton(
                            selected = selectedRoomId == room.roomId,
                            compact = true,
                        ),
                        onClick = { selectedRoomId = room.roomId },
                    )
                    if (index < filteredRooms.lastIndex) {
                        PreferenceDivider()
                    }
                }
            }
            PreferenceDivider()
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(WEAR_COMPANION_DIALOG_CANCEL_TAG),
                headlineContent = { Text(commonStrings.cancelLabel) },
                onClick = onDismissRequest,
            )
            PreferenceDivider()
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(WEAR_COMPANION_DIALOG_SAVE_TAG),
                headlineContent = { Text(commonStrings.saveLabel) },
                enabled = selectedRoomId != null,
                onClick = {
                    val selectedRoom = rooms.firstOrNull { it.roomId == selectedRoomId } ?: return@ListItem
                    onSubmit(selectedRoom)
                },
            )
        }
    }
}

@Composable
private fun rememberWearCompanionLongPressSectionStrings(): WearCompanionLongPressSectionStrings {
    return WearCompanionLongPressSectionStrings(
        sectionTitle = stringResource(R.string.screen_wear_companion_long_press_section_title),
        messagesLabel = stringResource(R.string.screen_wear_companion_long_press_messages_title),
        conversationsLabel = stringResource(R.string.screen_wear_companion_long_press_conversations_title),
    )
}

@Composable
private fun rememberWearCompanionCommonStrings(): WearCompanionCommonStrings {
    return WearCompanionCommonStrings(
        saveLabel = stringResource(CommonStrings.action_save),
        cancelLabel = stringResource(CommonStrings.action_cancel),
        searchLabel = stringResource(CommonStrings.action_search),
    )
}

@Composable
private fun rememberWearCompanionNotificationSectionStrings(): WearCompanionNotificationSectionStrings {
    return WearCompanionNotificationSectionStrings(
        sectionTitle = stringResource(R.string.screen_wear_companion_notifications_section),
        actionDescriptionFormat = stringResource(R.string.screen_wear_companion_send_test_notification_description),
    )
}

@Composable
private fun rememberWearCompanionVibrationSectionStrings(): WearCompanionVibrationSectionStrings {
    return WearCompanionVibrationSectionStrings(
        sectionTitle = stringResource(R.string.screen_wear_companion_vibration_section_title),
        conversationOverridesSectionTitle = stringResource(R.string.screen_wear_companion_conversation_vibration_section_title),
        conversationOverridesEmpty = stringResource(R.string.screen_wear_companion_conversation_vibration_empty),
        conversationPickerNoRooms = stringResource(R.string.screen_wear_companion_conversation_picker_no_rooms),
        conversationPickerEmpty = stringResource(R.string.screen_wear_companion_conversation_picker_empty),
        conversationPickerAllAdded = stringResource(R.string.screen_wear_companion_conversation_picker_all_added),
        conversationPickerTitle = stringResource(R.string.screen_wear_companion_conversation_picker_title),
        addConversationLabel = stringResource(R.string.screen_wear_companion_add_conversation_title),
        addConversationDescription = stringResource(R.string.screen_wear_companion_add_conversation_description),
        conversationRemoveLabel = stringResource(R.string.screen_wear_companion_conversation_remove_title),
        conversationRemoveDescription = stringResource(R.string.screen_wear_companion_conversation_remove_description),
        conversationTestLabel = stringResource(R.string.screen_wear_companion_conversation_test_title),
        conversationTestDescription = stringResource(R.string.screen_wear_companion_conversation_test_description),
        inheritLabel = stringResource(R.string.screen_wear_companion_conversation_vibration_inherit),
        inheritDescription = stringResource(R.string.screen_wear_companion_conversation_vibration_inherit_description),
        groupsLabel = stringResource(R.string.screen_wear_companion_vibration_groups),
        dmsLabel = stringResource(R.string.screen_wear_companion_vibration_dms),
        favoriteGroupsLabel = stringResource(R.string.screen_wear_companion_vibration_favorite_groups),
        favoriteDmsLabel = stringResource(R.string.screen_wear_companion_vibration_favorite_dms),
        silentLabel = stringResource(R.string.screen_wear_companion_vibration_pattern_silent),
        silentDescription = stringResource(R.string.screen_wear_companion_vibration_pattern_silent_description),
        defaultLabel = stringResource(R.string.screen_wear_companion_vibration_pattern_default),
        defaultDescription = stringResource(R.string.screen_wear_companion_vibration_pattern_default_description),
        doubleLabel = stringResource(R.string.screen_wear_companion_vibration_pattern_double),
        doubleDescription = stringResource(R.string.screen_wear_companion_vibration_pattern_double_description),
        longLabel = stringResource(R.string.screen_wear_companion_vibration_pattern_long),
        longDescription = stringResource(R.string.screen_wear_companion_vibration_pattern_long_description),
        tripleLabel = stringResource(R.string.screen_wear_companion_vibration_pattern_triple),
        tripleDescription = stringResource(R.string.screen_wear_companion_vibration_pattern_triple_description),
        pulseLabel = stringResource(R.string.screen_wear_companion_vibration_pattern_pulse),
        pulseDescription = stringResource(R.string.screen_wear_companion_vibration_pattern_pulse_description),
        escalatingLabel = stringResource(R.string.screen_wear_companion_vibration_pattern_escalating),
        escalatingDescription = stringResource(R.string.screen_wear_companion_vibration_pattern_escalating_description),
        customLabel = stringResource(R.string.screen_wear_companion_vibration_pattern_custom),
        customDescription = stringResource(R.string.screen_wear_companion_vibration_pattern_custom_description),
        customPatternDialogTitle = stringResource(R.string.screen_wear_companion_custom_pattern_dialog_title),
        customPatternFieldLabel = stringResource(R.string.screen_wear_companion_custom_pattern_field_label),
        customPatternDescription = stringResource(R.string.screen_wear_companion_custom_pattern_description),
        customPatternError = stringResource(R.string.screen_wear_companion_custom_pattern_error),
        customPatternTestLabel = stringResource(R.string.screen_wear_companion_custom_pattern_test),
        customPatternTestDescription = stringResource(R.string.screen_wear_companion_custom_pattern_test_description),
    )
}

@Composable
private fun rememberWearCompanionTileActionSectionStrings(): WearCompanionTileActionSectionStrings {
    return WearCompanionTileActionSectionStrings(
        sectionTitle = stringResource(R.string.screen_wear_companion_tile_action_section_title),
        recentTileLabel = stringResource(R.string.screen_wear_companion_tile_action_recent_title),
        favoriteTileLabel = stringResource(R.string.screen_wear_companion_tile_action_favorite_title),
        openConversationLabel = stringResource(R.string.screen_wear_companion_tile_action_open_conversation),
        readLatestLabel = stringResource(R.string.screen_wear_companion_tile_action_read_latest),
        quickReplyEmojiLabel = stringResource(R.string.screen_wear_companion_tile_action_quick_reply_emoji),
        quickReplyTextLabel = stringResource(R.string.screen_wear_companion_tile_action_quick_reply_text),
        quickReplyVoiceLabel = stringResource(R.string.screen_wear_companion_tile_action_quick_reply_voice),
        openLatestLabel = stringResource(R.string.screen_wear_companion_tile_action_open_latest),
    )
}

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

private val tileConversationActionOptions = listOf(
    WatchTileConversationAction.OPEN_CONVERSATION,
    WatchTileConversationAction.READ_LATEST,
    WatchTileConversationAction.QUICK_REPLY_EMOJI,
    WatchTileConversationAction.QUICK_REPLY_TEXT,
    WatchTileConversationAction.QUICK_REPLY_VOICE,
    WatchTileConversationAction.OPEN_LATEST,
)

@Suppress("DEPRECATION")
private fun WatchTileConversationAction.displayLabel(strings: WearCompanionTileActionSectionStrings): String = when (this) {
    WatchTileConversationAction.OPEN_CONVERSATION -> strings.openConversationLabel
    WatchTileConversationAction.READ_LATEST -> strings.readLatestLabel
    WatchTileConversationAction.QUICK_REPLY_EMOJI -> strings.quickReplyEmojiLabel
    WatchTileConversationAction.QUICK_REPLY_TEXT -> strings.quickReplyTextLabel
    WatchTileConversationAction.QUICK_REPLY_VOICE -> strings.quickReplyVoiceLabel
    WatchTileConversationAction.OPEN_LATEST -> strings.openLatestLabel
    WatchTileConversationAction.DIRECT_REPLY -> strings.quickReplyTextLabel
    WatchTileConversationAction.VOICE_RECORDING -> strings.quickReplyVoiceLabel
}

@Suppress("DEPRECATION")
private fun WatchTileConversationAction.normalizedTileAction(): WatchTileConversationAction = when (this) {
    WatchTileConversationAction.DIRECT_REPLY -> WatchTileConversationAction.QUICK_REPLY_TEXT
    WatchTileConversationAction.VOICE_RECORDING -> WatchTileConversationAction.QUICK_REPLY_VOICE
    else -> this
}


private fun WatchFavoriteRoom.toVibrationCategory(): WearCompanionVibrationCategory = when {
    isFavorite && kind == io.element.android.watchbridge.contract.WatchRoomKind.DM -> WearCompanionVibrationCategory.FAVORITE_DMS
    isFavorite -> WearCompanionVibrationCategory.FAVORITE_GROUPS
    kind == io.element.android.watchbridge.contract.WatchRoomKind.DM -> WearCompanionVibrationCategory.DMS
    else -> WearCompanionVibrationCategory.GROUPS
}

private fun WearCompanionVibrationCategory.displayLabel(strings: WearCompanionVibrationSectionStrings): String = when (this) {
    WearCompanionVibrationCategory.GROUPS -> strings.groupsLabel
    WearCompanionVibrationCategory.DMS -> strings.dmsLabel
    WearCompanionVibrationCategory.FAVORITE_GROUPS -> strings.favoriteGroupsLabel
    WearCompanionVibrationCategory.FAVORITE_DMS -> strings.favoriteDmsLabel
}

private fun WatchNotificationVibrationPattern.displayLabel(strings: WearCompanionVibrationSectionStrings): String = when (this) {
    WatchNotificationVibrationPattern.SILENT -> strings.silentLabel
    WatchNotificationVibrationPattern.DEFAULT -> strings.defaultLabel
    WatchNotificationVibrationPattern.DOUBLE -> strings.doubleLabel
    WatchNotificationVibrationPattern.LONG -> strings.longLabel
    WatchNotificationVibrationPattern.TRIPLE -> strings.tripleLabel
    WatchNotificationVibrationPattern.PULSE -> strings.pulseLabel
    WatchNotificationVibrationPattern.ESCALATING -> strings.escalatingLabel
    WatchNotificationVibrationPattern.CUSTOM -> strings.customLabel
}

private fun WatchNotificationVibrationPattern.displayDescription(strings: WearCompanionVibrationSectionStrings): String = when (this) {
    WatchNotificationVibrationPattern.SILENT -> strings.silentDescription
    WatchNotificationVibrationPattern.DEFAULT -> strings.defaultDescription
    WatchNotificationVibrationPattern.DOUBLE -> strings.doubleDescription
    WatchNotificationVibrationPattern.LONG -> strings.longDescription
    WatchNotificationVibrationPattern.TRIPLE -> strings.tripleDescription
    WatchNotificationVibrationPattern.PULSE -> strings.pulseDescription
    WatchNotificationVibrationPattern.ESCALATING -> strings.escalatingDescription
    WatchNotificationVibrationPattern.CUSTOM -> strings.customDescription
}

private fun WatchLongPressMessageAction.toSelectorOption(): WearCompanionSelectorOption<WatchLongPressMessageAction> {
    return WearCompanionSelectorOption(
        value = this,
        label = displayLabel(),
        tag = wearCompanionMessageActionOptionTag(this),
    )
}

private fun WatchLongPressConversationAction.toSelectorOption(): WearCompanionSelectorOption<WatchLongPressConversationAction> {
    return WearCompanionSelectorOption(
        value = this,
        label = displayLabel(),
        tag = wearCompanionConversationActionOptionTag(this),
    )
}

private fun WatchTileConversationAction.toSelectorOption(
    kind: WearCompanionTileKind,
    strings: WearCompanionTileActionSectionStrings,
): WearCompanionSelectorOption<WatchTileConversationAction> {
    return WearCompanionSelectorOption(
        value = this,
        label = displayLabel(strings),
        tag = wearCompanionTileActionOptionTag(kind, this),
    )
}

private fun WatchNotificationVibrationPattern.toSelectorOption(
    category: WearCompanionVibrationCategory,
    strings: WearCompanionVibrationSectionStrings,
): WearCompanionSelectorOption<WatchNotificationVibrationPattern> {
    return WearCompanionSelectorOption(
        value = this,
        label = displayLabel(strings),
        description = displayDescription(strings),
        tag = wearCompanionVibrationOptionTag(category, this),
    )
}

private fun WatchCompanionSettings.notificationVibrationOptionFor(
    category: WearCompanionVibrationCategory,
    strings: WearCompanionVibrationSectionStrings,
): WearCompanionSelectorOption<WatchNotificationVibrationPattern> {
    val pattern = notificationVibrationFor(category)
    return WearCompanionSelectorOption(
        value = pattern,
        label = pattern.displayLabel(strings),
        description = pattern.displayDescription(strings),
        tag = wearCompanionVibrationOptionTag(category, pattern),
    )
}

private fun WatchCompanionSettings.conversationVibrationOptionFor(
    roomId: String,
    strings: WearCompanionVibrationSectionStrings,
): WearCompanionSelectorOption<WatchNotificationVibrationPattern?> {
    val override = notificationVibrations.conversationOverrideFor(roomId)
    val pattern = override?.pattern
    return if (pattern == null) {
        WearCompanionSelectorOption(
            value = null,
            label = strings.inheritLabel,
            description = strings.inheritDescription,
            tag = wearCompanionConversationVibrationOptionTag(roomId, null),
        )
    } else {
        WearCompanionSelectorOption(
            value = pattern,
            label = pattern.displayLabel(strings),
            description = override.displayDescription(strings),
            tag = wearCompanionConversationVibrationOptionTag(roomId, pattern),
        )
    }
}

private fun conversationVibrationOptions(
    roomId: String,
    strings: WearCompanionVibrationSectionStrings,
): List<WearCompanionSelectorOption<WatchNotificationVibrationPattern?>> {
    val inheritOption = WearCompanionSelectorOption<WatchNotificationVibrationPattern?>(
        value = null,
        label = strings.inheritLabel,
        description = strings.inheritDescription,
        tag = wearCompanionConversationVibrationOptionTag(roomId, null),
    )
    return buildList {
        add(inheritOption)
        addAll(
            WatchNotificationVibrationPattern.entries.map { pattern ->
                WearCompanionSelectorOption(
                    value = pattern,
                    label = pattern.displayLabel(strings),
                    description = pattern.displayDescription(strings),
                    tag = wearCompanionConversationVibrationOptionTag(roomId, pattern),
                )
            },
        )
    }
}

private fun categorySelectableVibrationPatterns(): List<WatchNotificationVibrationPattern> {
    return WatchNotificationVibrationPattern.entries
}

internal fun WatchCompanionSettings.notificationVibrationFor(
    category: WearCompanionVibrationCategory,
): WatchNotificationVibrationPattern {
    return when (category) {
        WearCompanionVibrationCategory.GROUPS -> notificationVibrations.groups
        WearCompanionVibrationCategory.DMS -> notificationVibrations.dms
        WearCompanionVibrationCategory.FAVORITE_GROUPS -> notificationVibrations.favoriteGroups
        WearCompanionVibrationCategory.FAVORITE_DMS -> notificationVibrations.favoriteDms
    }
}

internal fun WatchCompanionSettings.notificationCustomPatternFor(
    category: WearCompanionVibrationCategory,
): String {
    return when (category) {
        WearCompanionVibrationCategory.GROUPS -> notificationVibrations.groupsCustomPattern
        WearCompanionVibrationCategory.DMS -> notificationVibrations.dmsCustomPattern
        WearCompanionVibrationCategory.FAVORITE_GROUPS -> notificationVibrations.favoriteGroupsCustomPattern
        WearCompanionVibrationCategory.FAVORITE_DMS -> notificationVibrations.favoriteDmsCustomPattern
    }
}

internal fun WatchCompanionSettings.withNotificationVibration(
    category: WearCompanionVibrationCategory,
    pattern: WatchNotificationVibrationPattern,
    customPattern: String = "",
): WatchCompanionSettings {
    return copy(
        notificationVibrations = notificationVibrations.withPattern(category, pattern, customPattern),
    )
}

internal fun WatchCompanionSettings.withConversationSelection(roomId: String): WatchCompanionSettings {
    if (notificationVibrations.conversationOverrideFor(roomId) != null) return this
    return copy(
        notificationVibrations = notificationVibrations.copy(
            conversationOverrides = notificationVibrations.conversationOverrides + WatchConversationVibrationOverride(
                roomId = roomId,
            ),
        ),
    )
}

internal fun WatchCompanionSettings.withConversationNotificationVibration(
    roomId: String,
    pattern: WatchNotificationVibrationPattern?,
    customPattern: String = "",
): WatchCompanionSettings {
    val updatedOverride = WatchConversationVibrationOverride(
        roomId = roomId,
        pattern = pattern,
        customPattern = customPattern,
    )
    val remainingOverrides = notificationVibrations.conversationOverrides.filterNot { it.roomId == roomId }
    return copy(
        notificationVibrations = notificationVibrations.copy(
            conversationOverrides = remainingOverrides + updatedOverride,
        ),
    )
}

internal fun WatchCompanionSettings.withoutConversationNotificationVibration(roomId: String): WatchCompanionSettings {
    return copy(
        notificationVibrations = notificationVibrations.copy(
            conversationOverrides = notificationVibrations.conversationOverrides.filterNot { it.roomId == roomId },
        ),
    )
}

private fun WatchNotificationVibrationSettings.withPattern(
    category: WearCompanionVibrationCategory,
    pattern: WatchNotificationVibrationPattern,
    customPattern: String = "",
): WatchNotificationVibrationSettings {
    return when (category) {
        WearCompanionVibrationCategory.GROUPS -> copy(groups = pattern, groupsCustomPattern = customPattern)
        WearCompanionVibrationCategory.DMS -> copy(dms = pattern, dmsCustomPattern = customPattern)
        WearCompanionVibrationCategory.FAVORITE_GROUPS -> copy(favoriteGroups = pattern, favoriteGroupsCustomPattern = customPattern)
        WearCompanionVibrationCategory.FAVORITE_DMS -> copy(favoriteDms = pattern, favoriteDmsCustomPattern = customPattern)
    }
}

private fun WatchNotificationVibrationSettings.conversationOverrideFor(
    roomId: String,
): WatchConversationVibrationOverride? {
    return conversationOverrides.firstOrNull { it.roomId == roomId }
}

private fun WatchConversationVibrationOverride.displayDescription(
    strings: WearCompanionVibrationSectionStrings,
): String {
    val selectedPattern = pattern ?: return strings.inheritDescription
    return selectedPattern.displayDescription(strings)
}

private fun WatchFavoriteRoom.matchesSearch(query: String): Boolean {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isBlank()) return true
    return displayNameOrRoomId().contains(normalizedQuery, ignoreCase = true)
}

private fun WatchFavoriteRoom.displayNameOrRoomId(): String {
    return displayName.takeIf { it.isNotBlank() } ?: roomId
}

@Composable
private fun WearCompanionCustomPatternDialog(
    roomTitle: String,
    commonStrings: WearCompanionCommonStrings,
    usePlatformDialog: Boolean,
    strings: WearCompanionVibrationSectionStrings,
    initialPattern: String,
    canSendTest: Boolean,
    onDismissRequest: () -> Unit,
    onSubmit: (String) -> Unit,
    onSendTest: (String) -> Unit,
) {
    var patternText by remember(roomTitle, initialPattern) { mutableStateOf(initialPattern) }
    val trimmedPattern = patternText.trim()
    val isValidPattern = trimmedPattern.isNotBlank() &&
        parseCustomWatchNotificationVibrationPattern(trimmedPattern) != null
    val error = when {
        trimmedPattern.isBlank() -> null
        isValidPattern -> null
        else -> strings.customPatternError
    }

    if (usePlatformDialog) {
        ListDialog(
            title = strings.customPatternDialogTitle,
            subtitle = roomTitle,
            onDismissRequest = onDismissRequest,
            onSubmit = { onSubmit(trimmedPattern) },
            cancelText = commonStrings.cancelLabel,
            submitText = commonStrings.saveLabel,
            enabled = isValidPattern,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            listItems = {
                item {
                    TextFieldListItem(
                        placeholder = strings.customPatternDescription,
                        text = patternText,
                        onTextChange = { patternText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(WEAR_COMPANION_CUSTOM_PATTERN_INPUT_TAG),
                        error = error,
                        label = strings.customPatternFieldLabel,
                        minLines = 2,
                        maxLines = 3,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    )
                }
                item {
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(WEAR_COMPANION_CUSTOM_PATTERN_TEST_TAG),
                        headlineContent = { Text(strings.customPatternTestLabel) },
                        supportingContent = { Text(strings.customPatternTestDescription) },
                        style = ListItemStyle.Primary,
                        enabled = canSendTest && isValidPattern,
                        onClick = { onSendTest(trimmedPattern) },
                    )
                }
            },
        )
    } else {
        Column {
            TextFieldListItem(
                placeholder = strings.customPatternDescription,
                text = patternText,
                onTextChange = { patternText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(WEAR_COMPANION_CUSTOM_PATTERN_INPUT_TAG),
                error = error,
                label = strings.customPatternFieldLabel,
                minLines = 2,
                maxLines = 3,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            )
            PreferenceDivider()
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(WEAR_COMPANION_CUSTOM_PATTERN_TEST_TAG),
                headlineContent = { Text(strings.customPatternTestLabel) },
                supportingContent = { Text(strings.customPatternTestDescription) },
                style = ListItemStyle.Primary,
                enabled = canSendTest && isValidPattern,
                onClick = { onSendTest(trimmedPattern) },
            )
            PreferenceDivider()
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(WEAR_COMPANION_DIALOG_CANCEL_TAG),
                headlineContent = { Text(commonStrings.cancelLabel) },
                onClick = onDismissRequest,
            )
            PreferenceDivider()
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(WEAR_COMPANION_DIALOG_SAVE_TAG),
                headlineContent = { Text(commonStrings.saveLabel) },
                enabled = isValidPattern,
                onClick = { onSubmit(trimmedPattern) },
            )
        }
    }
}

private fun customPatternDisplayDescription(
    customPattern: String,
    strings: WearCompanionVibrationSectionStrings,
): String {
    return if (customPattern.isBlank()) {
        strings.customDescription
    } else {
        "$strings.customDescription: $customPattern"
    }
}
