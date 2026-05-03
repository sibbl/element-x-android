/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.wear.phone.interactions.notifications.BridgingConfig
import androidx.wear.phone.interactions.notifications.BridgingManager
import androidx.wear.tiles.TileService
import io.element.android.appconfig.NotificationConfig
import io.element.android.wearapp.tile.FavoriteContactsTileService
import io.element.android.wearapp.bridge.WearBridgeClient
import io.element.android.wearapp.tile.RecentContactsTileService
import timber.log.Timber

/**
 * Watch-side `Application`. Owns the singleton [WearBridgeClient] used by every screen / service
 * to talk to the phone.
 */
class WearApp : Application() {

    lateinit var bridgeClient: WearBridgeClient
        private set

    override fun onCreate() {
        super.onCreate()
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            Timber.plant(Timber.DebugTree())
        }
        configureNotificationBridging()
        bridgeClient = WearBridgeClient(this)
        bridgeClient.start()
        // Request tile updates so both conversation tiles refresh when data becomes available.
        TileService.getUpdater(this).requestUpdate(RecentContactsTileService::class.java)
        TileService.getUpdater(this).requestUpdate(FavoriteContactsTileService::class.java)
    }

    override fun onTerminate() {
        bridgeClient.stop()
        super.onTerminate()
    }

    private fun configureNotificationBridging() {
        runCatching {
            BridgingManager.fromContext(this).setConfig(createWearNotificationBridgingConfig(this))
        }.onFailure { throwable ->
            Timber.w(throwable, "Unable to configure Wear notification bridging")
        }
    }
}

internal fun createWearNotificationBridgingConfig(context: Context): BridgingConfig {
    return BridgingConfig.Builder(context, false)
        .addExcludedTags(listOf(NotificationConfig.WEAR_BRIDGED_NOTIFICATION_TAG))
        .build()
}
