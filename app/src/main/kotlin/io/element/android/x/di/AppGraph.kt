/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.di

import android.content.Context
import androidx.work.ListenableWorker
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.Provides
import io.element.android.libraries.architecture.NodeFactoriesBindings
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.matrix.api.MatrixClientProvider
import io.element.android.libraries.matrix.ui.media.ImageLoaderHolder
import io.element.android.libraries.push.api.notifications.NotificationBitmapLoader
import io.element.android.libraries.push.api.notifications.NotificationCleaner
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.services.appnavstate.api.AppNavigationStateService
import io.element.android.libraries.workmanager.api.di.MetroWorkerFactory
import kotlin.reflect.KClass

@DependencyGraph(AppScope::class)
interface AppGraph : NodeFactoriesBindings {
    val sessionGraphFactory: SessionGraph.Factory
    val appNavigationStateService: AppNavigationStateService
    val matrixClientProvider: MatrixClientProvider
    val sessionStore: SessionStore
    val imageLoaderHolder: ImageLoaderHolder
    val notificationBitmapLoader: NotificationBitmapLoader
    val notificationCleaner: NotificationCleaner

    @Multibinds
    val workerProviders:
        Map<KClass<out ListenableWorker>, MetroWorkerFactory.WorkerInstanceFactory<*>>

    @DependencyGraph.Factory
    interface Factory {
        fun create(
            @ApplicationContext @Provides
            context: Context
        ): AppGraph
    }
}
