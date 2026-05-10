/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.wearapp.notifications

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class WearNotificationActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sourceIntent = intent
        if (sourceIntent?.action == WearNotificationActionReceiver.ACTION_MARK_AS_READ) {
            WearNotificationActionReceiver.SystemWearNotificationActionUiController()
                .showMarkAsReadConfirmation(this)
            sendBroadcast(
                Intent(this, WearNotificationActionReceiver::class.java)
                    .setAction(sourceIntent.action)
                    .putExtras(sourceIntent)
                    .putExtra(WearNotificationActionReceiver.EXTRA_SKIP_CONFIRMATION, true),
            )
        }
        finish()
    }
}
