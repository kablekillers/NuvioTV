package com.nuvio.tv.updater

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AbiSelectorTest {
    @Test fun matchesWholeAbiTokensOnly() {
        assertTrue(AbiSelector.mentionsAbi("Kable-Killers-TV-1.1.4-x86.apk", "x86"))
        assertFalse(AbiSelector.mentionsAbi("Kable-Killers-TV-1.1.4-x86_64.apk", "x86"))
        assertTrue(AbiSelector.mentionsAbi("Kable-Killers-TV-1.1.4-x86_64.apk", "x86_64"))
        assertTrue(AbiSelector.mentionsAbi("Kable-Killers-TV-1.1.4-arm64-v8a.apk", "arm64-v8a"))
        assertTrue(AbiSelector.mentionsAbi("app-full-armeabi-v7a-release.apk", "armeabi-v7a"))
        assertFalse(AbiSelector.mentionsAbi("Kable-Killers-TV-1.1.4.apk", "arm64-v8a"))
    }
}
