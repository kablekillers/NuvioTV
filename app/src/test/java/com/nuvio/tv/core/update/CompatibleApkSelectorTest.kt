package com.nuvio.tv.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompatibleApkSelectorTest {
    private val assets = listOf(
        "Kable-Killers-TV-full-armeabi-v7a-release.apk",
        "Kable-Killers-TV-full-arm64-v8a-release.apk",
    )

    @Test fun arm32OnlyRuntimeSelectsArm32() {
        assertEquals(assets[0], CompatibleApkSelector.select(assets, { it }, arrayOf("armeabi-v7a")))
    }

    @Test fun arm64RuntimeSelectsArm64EvenWhenArm32AlsoSupported() {
        assertEquals(assets[1], CompatibleApkSelector.select(assets, { it }, arrayOf("arm64-v8a", "armeabi-v7a")))
    }

    @Test fun unsupportedArchitectureDoesNotSelectIncompatibleApk() {
        assertNull(CompatibleApkSelector.select(assets, { it }, arrayOf("x86_64", "x86")))
    }

    @Test fun missingMatchingAssetDoesNotFallBackToWrongArchitecture() {
        assertNull(CompatibleApkSelector.select(listOf(assets[1]), { it }, arrayOf("armeabi-v7a")))
    }
}
