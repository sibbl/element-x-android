/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.ui.common

import android.content.Context
import androidx.annotation.StringRes
import io.element.android.watchbridge.contract.WatchErrorCode
import io.element.android.wearapp.R
import io.element.android.wearapp.bridge.WatchCommandException

internal fun Context.watchCommandErrorMessage(
    throwable: Throwable,
    @StringRes fallbackRes: Int,
): String = resolveWatchCommandErrorMessage(this, throwable, fallbackRes)

internal fun resolveWatchCommandErrorMessage(
    context: Context,
    throwable: Throwable,
    @StringRes fallbackRes: Int,
): String {
    val failure = throwable as? WatchCommandException
    return when (failure?.code) {
        WatchErrorCode.PHONE_APP_UNAVAILABLE -> context.getString(R.string.watch_error_phone_unavailable)
        WatchErrorCode.TIMEOUT -> context.getString(R.string.watch_error_timeout)
        WatchErrorCode.NETWORK -> context.getString(R.string.watch_error_network)
        WatchErrorCode.FEATURE_DISABLED -> context.getString(R.string.watch_error_update_required)
        WatchErrorCode.NOT_FOUND -> context.getString(R.string.watch_error_not_found)
        else -> context.getString(fallbackRes)
    }
}
