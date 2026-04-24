/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp

import android.app.Application
import android.content.pm.ApplicationInfo
import io.element.android.wearapp.bridge.WearBridgeClient
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
        bridgeClient = WearBridgeClient(this)
        bridgeClient.start()
    }

    override fun onTerminate() {
        bridgeClient.stop()
        super.onTerminate()
    }
}
