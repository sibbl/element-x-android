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
import timber.log.Timber

private const val DUPLICATE_CAPABILITY_STATUS_CODE = 4006

/**
 * Dynamically advertises the phone-side Wear bridge capability for sideloaded debug builds.
 *
 * Static `android_wear_capabilities` resources are still packaged, but dynamic registration makes
 * capability discovery reliable immediately after `adb install -r` without waiting for Play
 * Services to rescan the package.
 */
class WatchBridgeCapabilityInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        Wearable.getCapabilityClient(context)
            .addLocalCapability(WatchProtocol.PHONE_CAPABILITY)
            .addOnSuccessListener { Timber.d("WatchBridge phone capability registered") }
            .addOnFailureListener {
                if (it.isDuplicateCapability()) {
                    Timber.d("WatchBridge phone capability already registered")
                } else {
                    Timber.w(it, "WatchBridge phone capability registration failed")
                }
            }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()

    private fun Throwable.isDuplicateCapability(): Boolean =
        this is ApiException && statusCode == DUPLICATE_CAPABILITY_STATUS_CODE
}