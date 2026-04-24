/*
 * Copyright 2026 New Vector Ltd.
 * Wear OS companion fork.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

plugins {
    id("io.element.jvm-library")
}

dependencies {
    api(project(":watchbridge-contract"))
    api(libs.coroutines.core)
    api(libs.coroutines.test)
    api(libs.test.junit)
    api(libs.test.truth)
}
