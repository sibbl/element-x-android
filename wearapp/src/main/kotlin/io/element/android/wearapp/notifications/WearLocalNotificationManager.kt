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
import io.element.android.watchbridge.contract.WatchNotificationVibrationPattern
import io.element.android.wearapp.R
import timber.log.Timber
import java.security.MessageDigest

internal class WearLocalNotificationManager(
    private val context: Context,
    private val factory: WearLocalNotificationFactory = WearLocalNotificationFactory(context),
    private val dismissalStore: WearNotificationDismissalStore = WearNotificationDismissalStore(context),
    private val notificationManagerCompat: NotificationManagerCompat = NotificationManagerCompat.from(context),
    private val notificationsAllowedProvider: () -> Boolean = {
        defaultCanNotify(context, notificationManagerCompat)
    },
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
        if (!notificationsAllowedProvider()) {
            Timber.w("Not allowed to show local watch notifications")
            return
        }
        val resolvedVibration = factory.selectedVibration(notification)
        val channelId = ensureChannel(
            resolvedVibration = resolvedVibration,
            requestedChannelId = wearLocalNotificationChannelId(resolvedVibration),
        )
        // Reposting the same ID updates the notification atomically. A preceding cancel is
        // asynchronous on Wear OS and can race the repost, suppressing its alert/peek.
        notificationManagerCompat.notify(
            wearLocalNotificationId(notification.notificationKey),
            factory.build(notification, generatedAtMs, expiresAtMs, resolvedVibration, channelId),
        )
    }

    fun dismiss(notificationKey: String) {
        notificationManagerCompat.cancel(wearLocalNotificationId(notificationKey))
    }

    private fun ensureChannel(
        resolvedVibration: WearResolvedNotificationVibration,
        requestedChannelId: String,
    ): String {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return requestedChannelId
        val desiredChannel = buildWearLocalNotificationChannel(context, resolvedVibration, requestedChannelId)
        val expectedConfigurations = context.getSharedPreferences(CHANNEL_CONFIG_PREFERENCES, Context.MODE_PRIVATE)
        val desiredConfiguration = desiredChannel.configFingerprint()
        val existingChannel = manager.getNotificationChannel(requestedChannelId)
        if (existingChannel == null) {
            manager.createNotificationChannel(desiredChannel)
            expectedConfigurations.edit().putString(requestedChannelId, desiredConfiguration).apply()
            return requestedChannelId
        }
        if (existingChannel.matchesAlertConfiguration(desiredChannel)) {
            expectedConfigurations.edit().putString(requestedChannelId, desiredConfiguration).apply()
            return requestedChannelId
        }

        // Android freezes vibration/sound/importance after a channel is first created. A channel
        // left behind by an older app build therefore cannot be repaired in place. Only migrate
        // channels whose app-created configuration is unknown/stale; once we have recorded the
        // desired configuration, a later difference is a user choice and must be preserved.
        if (expectedConfigurations.getString(requestedChannelId, null) == desiredConfiguration) {
            return requestedChannelId
        }
        val replacementChannelId = "${requestedChannelId}_cfg_${desiredConfiguration.stableShortHash()}"
        if (manager.getNotificationChannel(replacementChannelId) == null) {
            manager.createNotificationChannel(
                buildWearLocalNotificationChannel(context, resolvedVibration, replacementChannelId),
            )
        }
        expectedConfigurations.edit().putString(replacementChannelId, desiredConfiguration).apply()
        Timber.i("Migrated stale watch notification channel old=%s new=%s", requestedChannelId, replacementChannelId)
        return replacementChannelId
    }

    companion object {
        // Versioned so upgraded installs receive the complete channel-owned waveform.
        internal const val CHANNEL_PREFIX = "wear_companion_messages_v23_"
        internal const val CHANNEL_ID = "${CHANNEL_PREFIX}generic_default"
        private const val CHANNEL_CONFIG_PREFERENCES = "wear_notification_channel_configs"
    }
}

private fun NotificationChannel.matchesAlertConfiguration(desired: NotificationChannel): Boolean =
    importance == desired.importance &&
        shouldVibrate() == desired.shouldVibrate() &&
        vibrationPattern?.toList() == desired.vibrationPattern?.toList() &&
        sound == desired.sound

private fun NotificationChannel.configFingerprint(): String {
    val vibration = vibrationPattern?.joinToString(separator = ",") ?: if (shouldVibrate()) "system" else "off"
    return "$importance|$vibration|${sound ?: "none"}"
}

private fun parseCustomVibrationPattern(spec: String?): LongArray? {
    val normalized = spec
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    val tokens = normalized.split(Regex("[\\s,;]+")).filter { it.isNotBlank() }
    if (tokens.isEmpty() || tokens.size > 16) return null

    val timings = LongArray(tokens.size + 1)
    timings[0] = 0L
    var totalDurationMs = 0L
    tokens.forEachIndexed { index, token ->
        val durationMs = token.toLongOrNull()
            ?.takeIf { it in 1L..5000L }
            ?: return null
        totalDurationMs += durationMs
        if (totalDurationMs > 20_000L) return null
        timings[index + 1] = durationMs
    }
    return timings
}

private fun defaultCanNotify(
    context: Context,
    notificationManagerCompat: NotificationManagerCompat,
): Boolean {
    return ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
        notificationManagerCompat.areNotificationsEnabled()
}

internal fun buildWearLocalNotificationChannels(context: Context): List<NotificationChannel> =
    WatchNotificationVibrationPattern.entries
        .filterNot { it == WatchNotificationVibrationPattern.DEFAULT }
        .map { pattern ->
            buildWearLocalNotificationChannel(context, pattern)
        }

internal fun buildWearLocalNotificationChannel(
    context: Context,
    pattern: WatchNotificationVibrationPattern,
): NotificationChannel = buildWearLocalNotificationChannel(
    context = context,
    vibration = WearResolvedNotificationVibration(pattern),
)

internal fun buildWearLocalNotificationChannel(
    context: Context,
    vibration: WearResolvedNotificationVibration,
    channelId: String = wearLocalNotificationChannelId(vibration),
): NotificationChannel = NotificationChannel(
    channelId,
    wearLocalNotificationChannelName(context, vibration.pattern),
    NotificationManager.IMPORTANCE_HIGH,
).apply {
    setShowBadge(false)
    setSound(null, null)
    when (vibration.pattern) {
        WatchNotificationVibrationPattern.SILENT -> enableVibration(false)
        WatchNotificationVibrationPattern.DEFAULT -> enableVibration(true)
        else -> {
            // Keep custom channels alerting so Wear OS shows a visual peek, and let the
            // notification channel own the complete waveform.
            enableVibration(true)
            setVibrationPattern(vibration.platformVibrationPattern())
        }
    }
}

internal fun wearLocalNotificationChannelId(vibration: WearResolvedNotificationVibration): String = when (vibration.pattern) {
    WatchNotificationVibrationPattern.DEFAULT -> "${WearLocalNotificationManager.CHANNEL_PREFIX}${vibration.source.idPart}_default"
    WatchNotificationVibrationPattern.SILENT -> "${WearLocalNotificationManager.CHANNEL_PREFIX}${vibration.source.idPart}_silent"
    WatchNotificationVibrationPattern.DOUBLE -> "${WearLocalNotificationManager.CHANNEL_PREFIX}${vibration.source.idPart}_double"
    WatchNotificationVibrationPattern.LONG -> "${WearLocalNotificationManager.CHANNEL_PREFIX}${vibration.source.idPart}_long"
    WatchNotificationVibrationPattern.TRIPLE -> "${WearLocalNotificationManager.CHANNEL_PREFIX}${vibration.source.idPart}_triple"
    WatchNotificationVibrationPattern.PULSE -> "${WearLocalNotificationManager.CHANNEL_PREFIX}${vibration.source.idPart}_pulse"
    WatchNotificationVibrationPattern.ESCALATING -> "${WearLocalNotificationManager.CHANNEL_PREFIX}${vibration.source.idPart}_escalating"
    WatchNotificationVibrationPattern.CUSTOM -> "${WearLocalNotificationManager.CHANNEL_PREFIX}${vibration.source.idPart}_custom_${vibration.customPattern.stableShortHash()}"
}

internal fun wearLocalNotificationChannelId(pattern: WatchNotificationVibrationPattern): String = when (pattern) {
    WatchNotificationVibrationPattern.DEFAULT -> WearLocalNotificationManager.CHANNEL_ID
    WatchNotificationVibrationPattern.SILENT -> "${WearLocalNotificationManager.CHANNEL_PREFIX}generic_silent"
    WatchNotificationVibrationPattern.DOUBLE -> "${WearLocalNotificationManager.CHANNEL_PREFIX}generic_double"
    WatchNotificationVibrationPattern.LONG -> "${WearLocalNotificationManager.CHANNEL_PREFIX}generic_long"
    WatchNotificationVibrationPattern.TRIPLE -> "${WearLocalNotificationManager.CHANNEL_PREFIX}generic_triple"
    WatchNotificationVibrationPattern.PULSE -> "${WearLocalNotificationManager.CHANNEL_PREFIX}generic_pulse"
    WatchNotificationVibrationPattern.ESCALATING -> "${WearLocalNotificationManager.CHANNEL_PREFIX}generic_escalating"
    WatchNotificationVibrationPattern.CUSTOM -> "${WearLocalNotificationManager.CHANNEL_PREFIX}generic_custom"
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

internal fun WearResolvedNotificationVibration.platformVibrationPattern(): LongArray = when (pattern) {
    WatchNotificationVibrationPattern.DOUBLE -> longArrayOf(0L, 90L, 90L, 170L)
    WatchNotificationVibrationPattern.LONG -> longArrayOf(0L, 800L)
    WatchNotificationVibrationPattern.TRIPLE -> longArrayOf(0L, 70L, 70L, 100L, 70L, 130L)
    WatchNotificationVibrationPattern.PULSE -> longArrayOf(0L, 120L, 110L, 120L, 110L, 120L, 110L, 120L)
    WatchNotificationVibrationPattern.ESCALATING -> longArrayOf(0L, 60L, 70L, 110L, 70L, 220L)
    WatchNotificationVibrationPattern.CUSTOM -> parseCustomVibrationPattern(customPattern) ?: longArrayOf(0L, 100L)
    WatchNotificationVibrationPattern.SILENT,
    WatchNotificationVibrationPattern.DEFAULT -> error("No explicit vibration pattern for $pattern")
}

internal fun String.stableShortHash(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
    return digest.take(4).joinToString(separator = "") { byte -> "%02x".format(byte) }
}

internal fun List<Long>.stableShortHash(): String {
    val input = joinToString(separator = ",")
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return digest.take(4).joinToString(separator = "") { byte -> "%02x".format(byte) }
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
