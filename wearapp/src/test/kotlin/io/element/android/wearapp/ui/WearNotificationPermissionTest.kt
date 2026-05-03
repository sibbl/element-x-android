/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class WearNotificationPermissionTest {

    @Test
    fun `android 13 without permission requires runtime request`() {
        val state = resolveWearNotificationPermissionState(
            sdkInt = 33,
            permissionGranted = false,
            notificationsEnabled = false,
        )

        assertThat(state).isEqualTo(WearNotificationPermissionState.NeedsRuntimePermission)
    }

    @Test
    fun `disabled notifications after permission grant require settings`() {
        val state = resolveWearNotificationPermissionState(
            sdkInt = 33,
            permissionGranted = true,
            notificationsEnabled = false,
        )

        assertThat(state).isEqualTo(WearNotificationPermissionState.DisabledInSettings)
    }

    @Test
    fun `enabled notifications return granted state`() {
        val state = resolveWearNotificationPermissionState(
            sdkInt = 33,
            permissionGranted = true,
            notificationsEnabled = true,
        )

        assertThat(state).isEqualTo(WearNotificationPermissionState.Granted)
    }

    @Test
    fun `wear manifest declares post notifications permission`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        @Suppress("DEPRECATION")
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)

        assertThat(packageInfo.requestedPermissions.orEmpty().toList()).contains(Manifest.permission.POST_NOTIFICATIONS)
    }
}