package com.nuvio.tv.core.update

import android.os.Build

/**
 * Chooses a compatible architecture-specific release asset.
 *
 * IMPORTANT: Build.SUPPORTED_ABIS reflects the Android OS app runtime, not merely
 * the device CPU. A 64-bit CPU running 32-bit Android must receive ARMv7.
 * This helper does not download or install an APK.
 */
internal object CompatibleApkSelector {
    private const val ARM64 = "arm64-v8a"
    private const val ARM32 = "armeabi-v7a"

    /**
     * Prefer the 64-bit APK on a 64-bit-capable Android runtime, otherwise ARMv7.
     * Return null for devices that support neither architecture.
     */
    fun preferredAbi(supportedAbis: Array<String> = Build.SUPPORTED_ABIS): String? =
        when {
            supportedAbis.contains(ARM64) -> ARM64
            supportedAbis.contains(ARM32) -> ARM32
            else -> null
        }

    /**
     * Match an exact ABI marker in an APK asset filename.
     * Avoid substring matching against URLs or release descriptions.
     */
    fun <T> select(
        assets: List<T>,
        filename: (T) -> String,
        supportedAbis: Array<String> = Build.SUPPORTED_ABIS,
    ): T? {
        val abi = preferredAbi(supportedAbis) ?: return null
        return assets.firstOrNull { asset ->
            val name = filename(asset).lowercase()
            name.endsWith(".apk") && (
                name.contains("-${abi.lowercase()}-") ||
                name.contains("_${abi.lowercase()}_") ||
                name.contains("-${abi.lowercase()}.apk") ||
                name.contains("_${abi.lowercase()}.apk")
            )
        }
    }
}
