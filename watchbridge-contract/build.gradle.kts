/*
 * Copyright 2026 New Vector Ltd.
 * Wear OS companion fork.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

plugins {
    id("io.element.jvm-library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.serialization.json)

    testImplementation(libs.test.junit)
    testImplementation(libs.test.truth)
}
