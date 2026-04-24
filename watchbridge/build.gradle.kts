/*
 * Copyright 2026 New Vector Ltd.
 * Wear OS companion fork.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

plugins {
    id("io.element.android-library")
}

android {
    namespace = "io.element.android.watchbridge"
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    api(project(":watchbridge-contract"))

    // Wear OS Data Layer (works on the phone as well as the watch).
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

    implementation(libs.androidx.corektx)
    implementation(libs.coroutines.core)
    implementation(libs.timber)

    testImplementation(libs.test.junit)
    testImplementation(libs.test.truth)
    testImplementation(libs.coroutines.test)
    testImplementation(project(":watchbridge-testing"))
}
