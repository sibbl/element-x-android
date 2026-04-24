/*
 * Copyright 2026 New Vector Ltd.
 * Wear OS companion fork.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

plugins {
    id("io.element.android-compose-application")
}

android {
    namespace = "io.element.android.wearapp"

    defaultConfig {
        applicationId = "io.element.android.wearapp"
        minSdk = 30 // Wear OS 3.0+
        targetSdk = Versions.TARGET_SDK
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

}

dependencies {
    implementation(project(":watchbridge-contract"))

    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("androidx.wear.compose:compose-material:1.5.0")
    implementation("androidx.wear.compose:compose-foundation:1.5.0")
    implementation("androidx.wear.compose:compose-navigation:1.5.0")

    implementation(libs.androidx.corektx)
    implementation(libs.coroutines.core)
    implementation(libs.timber)

    testImplementation(libs.test.junit)
    testImplementation(libs.test.truth)
    testImplementation(libs.coroutines.test)
    testImplementation(project(":watchbridge-testing"))
}
