/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import io.element.android.watchbridge.contract.WatchMessageNotification
import io.element.android.wearapp.R
import timber.log.Timber

internal class WearLocalNotificationManager(
    private val context: Context,
    private val factory: WearLocalNotificationFactory = WearLocalNotificationFactory(context),
    private val dismissalStore: WearNotificationDismissalStore = WearNotificationDismissalStore(context),
    private val notificationManagerCompat: NotificationManagerCompat = NotificationManagerCompat.from(context),
) {

    fun show(
        notification: WatchMessageNotification,
        generatedAtMs: Long,
        expiresAtMs: Long?,
    ) {
        if (expiresAtMs != null && expiresAtMs <= System.currentTimeMillis()) {
            dismiss(notification.notificationKey)
            return
        }
        if (dismissalStore.wasDismissedSince(notification.notificationKey, generatedAtMs)) {
            Timber.d("Suppressing dismissed watch notification key=%s", notification.notificationKey)
            return
        }
        if (!canNotify()) {
            Timber.w("Not allowed to show local watch notifications")
            return
        }
        ensureChannel()
        notificationManagerCompat.notify(
            wearLocalNotificationId(notification.notificationKey),
            factory.build(notification, generatedAtMs, expiresAtMs),
        )
    }

    fun dismiss(notificationKey: String) {
        notificationManagerCompat.cancel(wearLocalNotificationId(notificationKey))
    }

    private fun canNotify(): Boolean {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            notificationManagerCompat.areNotificationsEnabled()
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.screen_wear_local_notifications_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            setShowBadge(false)
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        internal const val CHANNEL_ID = "wear_companion_messages"
    }
}

internal class WearNotificationDismissalStore(
    context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun wasDismissedSince(notificationKey: String, generatedAtMs: Long): Boolean {
        return preferences.getLong(prefKey(notificationKey), Long.MIN_VALUE) >= generatedAtMs
    }

    fun recordDismissal(notificationKey: String, generatedAtMs: Long) {
        pruneStaleEntries()
        preferences.edit()
            .putLong(prefKey(notificationKey), generatedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis())
            .apply()
    }

    private fun pruneStaleEntries() {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        val staleKeys = preferences.all
            .filterKeys { it.startsWith(PREFERENCE_KEY_PREFIX) }
            .filterValues { value -> (value as? Long ?: Long.MAX_VALUE) < cutoff }
            .keys
        if (staleKeys.isEmpty()) return
        preferences.edit().apply {
            staleKeys.forEach(::remove)
        }.apply()
    }

    private fun prefKey(notificationKey: String): String = "$PREFERENCE_KEY_PREFIX$notificationKey"

    private companion object {
        private const val PREFERENCES_NAME = "wear_local_notification_dismissals"
        private const val PREFERENCE_KEY_PREFIX = "dismissed:"
        private const val RETENTION_MS = 30L * 24L * 60L * 60L * 1000L
    }
}

internal fun wearLocalNotificationId(notificationKey: String): Int = notificationKey.hashCode()