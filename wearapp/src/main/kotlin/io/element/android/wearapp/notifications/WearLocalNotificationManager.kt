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
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import io.element.android.watchbridge.contract.WatchMessageNotification
import io.element.android.watchbridge.contract.WatchNotificationVibrationPattern
import io.element.android.wearapp.R
import timber.log.Timber

internal class WearLocalNotificationManager(
    private val context: Context,
    private val factory: WearLocalNotificationFactory = WearLocalNotificationFactory(context),
    private val dismissalStore: WearNotificationDismissalStore = WearNotificationDismissalStore(context),
    private val notificationManagerCompat: NotificationManagerCompat = NotificationManagerCompat.from(context),
    private val hapticPlayer: WearLocalNotificationHapticPlayer = SystemWearLocalNotificationHapticPlayer(context),
    private val notificationsAllowedProvider: () -> Boolean = {
        defaultCanNotify(context, notificationManagerCompat)
    },
    private val manualHapticsAllowedProvider: () -> Boolean = {
        defaultCanPlayManualHaptics(context)
    },
) {
    private var channelsEnsured = false

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
        if (!notificationsAllowedProvider()) {
            Timber.w("Not allowed to show local watch notifications")
            return
        }
        ensureChannels()
        val resolvedVibration = factory.selectedVibration(notification)
        notificationManagerCompat.notify(
            wearLocalNotificationId(notification.notificationKey),
            factory.build(notification, generatedAtMs, expiresAtMs),
        )
        if (resolvedVibration.usesManualWatchHaptics() && manualHapticsAllowedProvider()) {
            hapticPlayer.play(resolvedVibration)
        }
    }

    fun dismiss(notificationKey: String) {
        notificationManagerCompat.cancel(wearLocalNotificationId(notificationKey))
    }

    private fun ensureChannels() {
        if (channelsEnsured) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        buildWearLocalNotificationChannels(context).forEach(manager::createNotificationChannel)
        channelsEnsured = true
    }

    companion object {
        // Versioned so previously created silent/broken channels do not keep overriding the
        // restored default vibration behavior on upgraded installs, and so updated custom
        // channel recipes actually apply when we tweak them. Bumped again so manual haptic
        // channels still give Wear OS a tiny system vibration signal for peek presentation.
        internal const val CHANNEL_ID = "wear_companion_messages_v8_default"
    }
}

internal interface WearLocalNotificationHapticPlayer {
    fun play(vibration: WearResolvedNotificationVibration)
}

internal class SystemWearLocalNotificationHapticPlayer(
    private val context: Context,
) : WearLocalNotificationHapticPlayer {
    override fun play(vibration: WearResolvedNotificationVibration) {
        if (!vibration.usesManualWatchHaptics()) {
            return
        }
        val vibrator = context.defaultVibrator()?.takeIf { it.hasVibrator() } ?: return
        vibrator.vibrate(vibration.manualVibrationEffect())
    }
}

private fun Context.defaultVibrator(): Vibrator? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
}

private fun defaultCanNotify(
    context: Context,
    notificationManagerCompat: NotificationManagerCompat,
): Boolean {
    return ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
        notificationManagerCompat.areNotificationsEnabled()
}

private fun defaultCanPlayManualHaptics(context: Context): Boolean {
    val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return true
    return when (notificationManager.currentInterruptionFilter) {
        NotificationManager.INTERRUPTION_FILTER_ALL,
        NotificationManager.INTERRUPTION_FILTER_UNKNOWN -> true
        else -> false
    }
}

private fun WearResolvedNotificationVibration.usesManualWatchHaptics(): Boolean {
    return pattern != WatchNotificationVibrationPattern.SILENT && pattern != WatchNotificationVibrationPattern.DEFAULT
}

internal fun buildWearLocalNotificationChannels(context: Context): List<NotificationChannel> =
    WatchNotificationVibrationPattern.entries.map { pattern ->
        buildWearLocalNotificationChannel(context, pattern)
    }

internal fun buildWearLocalNotificationChannel(
    context: Context,
    pattern: WatchNotificationVibrationPattern,
): NotificationChannel = NotificationChannel(
    wearLocalNotificationChannelId(pattern),
    wearLocalNotificationChannelName(context, pattern),
    NotificationManager.IMPORTANCE_HIGH,
).apply {
    setShowBadge(false)
    setSound(null, null)
    when (pattern) {
        WatchNotificationVibrationPattern.SILENT -> enableVibration(false)
        WatchNotificationVibrationPattern.DEFAULT -> enableVibration(true)
        else -> {
            enableVibration(true)
            setVibrationPattern(longArrayOf(0L, 1L))
        }
    }
}

internal fun wearLocalNotificationChannelId(pattern: WatchNotificationVibrationPattern): String = when (pattern) {
    WatchNotificationVibrationPattern.DEFAULT -> WearLocalNotificationManager.CHANNEL_ID
    WatchNotificationVibrationPattern.SILENT -> "wear_companion_messages_v8_silent"
    WatchNotificationVibrationPattern.DOUBLE -> "wear_companion_messages_v8_double"
    WatchNotificationVibrationPattern.LONG -> "wear_companion_messages_v8_long"
    WatchNotificationVibrationPattern.TRIPLE -> "wear_companion_messages_v8_triple"
    WatchNotificationVibrationPattern.PULSE -> "wear_companion_messages_v8_pulse"
    WatchNotificationVibrationPattern.ESCALATING -> "wear_companion_messages_v8_escalating"
    WatchNotificationVibrationPattern.CUSTOM -> "wear_companion_messages_v8_custom"
}

private fun wearLocalNotificationChannelName(
    context: Context,
    pattern: WatchNotificationVibrationPattern,
): String = when (pattern) {
    WatchNotificationVibrationPattern.DEFAULT -> context.getString(R.string.screen_wear_local_notifications_channel_name_default)
    WatchNotificationVibrationPattern.SILENT -> context.getString(R.string.screen_wear_local_notifications_channel_name_silent)
    WatchNotificationVibrationPattern.DOUBLE -> context.getString(R.string.screen_wear_local_notifications_channel_name_double)
    WatchNotificationVibrationPattern.LONG -> context.getString(R.string.screen_wear_local_notifications_channel_name_long)
    WatchNotificationVibrationPattern.TRIPLE -> context.getString(R.string.screen_wear_local_notifications_channel_name_triple)
    WatchNotificationVibrationPattern.PULSE -> context.getString(R.string.screen_wear_local_notifications_channel_name_pulse)
    WatchNotificationVibrationPattern.ESCALATING -> context.getString(R.string.screen_wear_local_notifications_channel_name_escalating)
    WatchNotificationVibrationPattern.CUSTOM -> context.getString(R.string.screen_wear_local_notifications_channel_name_custom)
}

private fun WearResolvedNotificationVibration.manualVibrationEffect(): VibrationEffect = when (pattern) {
    WatchNotificationVibrationPattern.DOUBLE -> VibrationEffect.createWaveform(
        longArrayOf(0L, 90L, 90L, 170L),
        intArrayOf(0, 255, 0, 220),
        -1,
    )
    WatchNotificationVibrationPattern.LONG -> VibrationEffect.createOneShot(
        800L,
        VibrationEffect.DEFAULT_AMPLITUDE,
    )
    WatchNotificationVibrationPattern.TRIPLE -> VibrationEffect.createWaveform(
        longArrayOf(0L, 70L, 70L, 100L, 70L, 130L),
        intArrayOf(0, 180, 0, 220, 0, 255),
        -1,
    )
    WatchNotificationVibrationPattern.PULSE -> VibrationEffect.createWaveform(
        longArrayOf(0L, 120L, 110L, 120L, 110L, 120L, 110L, 120L),
        intArrayOf(0, 170, 0, 170, 0, 170, 0, 170),
        -1,
    )
    WatchNotificationVibrationPattern.ESCALATING -> VibrationEffect.createWaveform(
        longArrayOf(0L, 60L, 70L, 110L, 70L, 220L),
        intArrayOf(0, 100, 0, 180, 0, 255),
        -1,
    )
    WatchNotificationVibrationPattern.CUSTOM -> VibrationEffect.createWaveform(
        customTimingsMs.toLongArray(),
        -1,
    )
    WatchNotificationVibrationPattern.SILENT,
    WatchNotificationVibrationPattern.DEFAULT -> error("No explicit vibration effect for $pattern")
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
