/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.initializer

import android.content.Context
import androidx.startup.Initializer
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.wearable.Wearable
import io.element.android.watchbridge.contract.WatchProtocol
import io.element.android.x.watchbridge.ElementXWatchBridgeRuntime
import timber.log.Timber

private const val DUPLICATE_CAPABILITY_STATUS_CODE = 4006
private const val WATCH_BRIDGE_STARTUP_DELAY_MS = 5_000L

/**
 * Dynamically advertises the phone-side Wear bridge capability for sideloaded debug/release builds.
 *
 * Static legacy `android_wear_capabilities` resources are still packaged, but dynamic registration
 * makes application-id-scoped capability discovery reliable immediately after `adb install -r`
 * without waiting for Play Services to rescan the package.
 */
class WatchBridgeCapabilityInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        ElementXWatchBridgeRuntime.start(
            context = context.applicationContext,
            startupDelayMs = WATCH_BRIDGE_STARTUP_DELAY_MS,
        )
        Wearable.getCapabilityClient(context)
            .addLocalCapability(WatchProtocol.phoneCapability(context.packageName))
            .addOnSuccessListener { Timber.d("WatchBridge phone capability registered package=%s", context.packageName) }
            .addOnFailureListener {
                if (it.isDuplicateCapability()) {
                    Timber.d("WatchBridge phone capability already registered package=%s", context.packageName)
                } else {
                    Timber.w(it, "WatchBridge phone capability registration failed")
                }
            }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = listOf(
        androidx.lifecycle.ProcessLifecycleInitializer::class.java
    )

    private fun Throwable.isDuplicateCapability(): Boolean =
        this is ApiException && statusCode == DUPLICATE_CAPABILITY_STATUS_CODE
}
