/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.watchbridge

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.element.android.appconfig.NotificationConfig
import io.element.android.libraries.designsystem.utils.CommonDrawables
import io.element.android.x.R

private const val WEAR_COMPANION_TEST_CHANNEL_ID = "wear_companion_test"
private const val WEAR_COMPANION_TEST_NOTIFICATION_TAG = "wear-companion-test"
private const val WEAR_COMPANION_TEST_NOTIFICATION_ID = 9031

internal class WearCompanionTestNotificationSender(
    private val context: Context,
) {
    fun send() {
        val notificationManager = NotificationManagerCompat.from(context)
        notificationManager.createNotificationChannel(
            NotificationChannelCompat.Builder(
                WEAR_COMPANION_TEST_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH,
            )
                .setName(context.getString(R.string.screen_wear_companion_test_notification_channel_name))
                .setDescription(context.getString(R.string.screen_wear_companion_test_notification_channel_description))
                .build(),
        )

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, WearCompanionSettingsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, WEAR_COMPANION_TEST_CHANNEL_ID)
            .setSmallIcon(CommonDrawables.ic_notification)
            .setContentTitle(context.getString(R.string.screen_wear_companion_test_notification_title))
            .setContentText(context.getString(R.string.screen_wear_companion_test_notification_body))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.screen_wear_companion_test_notification_body)),
            )
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .extend(
                NotificationCompat.WearableExtender()
                    .setBridgeTag(NotificationConfig.WEAR_BRIDGED_NOTIFICATION_TAG)
                    .setDismissalId("wear-companion-test:${System.currentTimeMillis()}")
                    .setStartScrollBottom(true),
            )
            .build()

        notificationManager.notify(
            WEAR_COMPANION_TEST_NOTIFICATION_TAG,
            WEAR_COMPANION_TEST_NOTIFICATION_ID,
            notification,
        )
    }
}