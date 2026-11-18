package com.nuvio.tv.updater

import android.os.Build
import com.nuvio.tv.data.remote.dto.GitHubAssetDto

internal object AbiSelector {

    private val knownAbis = listOf(
        "arm64-v8a",
        "armeabi-v7a",
        "x86_64",
        "x86"
    )

    fun chooseBestApkAsset(assets: List<GitHubAssetDto>): GitHubAssetDto? {
        val apkAssets = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (apkAssets.isEmpty()) return null
        if (apkAssets.size == 1) return apkAssets.first()

        val supported = Build.SUPPORTED_ABIS?.toList().orEmpty()

        // Prefer exact ABI match (in device preference order)
        for (abi in supported) {
            val candidate = apkAssets.firstOrNull { mentionsAbi(it.name, abi) }
            if (candidate != null) return candidate
        }

        // Fallback to a universal APK if present
        val universal = apkAssets.firstOrNull {
            val n = it.name.lowercase()
            n.contains("universal") || n.contains("all") || n.contains("universal-release")
        }
        if (universal != null) return universal

        // If we can at least avoid wrong-ABI picks, prefer APKs that don't mention a known ABI.
        val noAbiMention = apkAssets.firstOrNull { asset ->
            knownAbis.none { abi -> mentionsAbi(asset.name, abi) }
        }
        return noAbiMention ?: apkAssets.first()
    }

    /** Whole-token match so "x86" never matches an "x86_64" APK. */
    internal fun mentionsAbi(fileName: String, abi: String): Boolean =
        Regex("(^|[^a-z0-9_])${Regex.escape(abi)}([^a-z0-9_]|$)", RegexOption.IGNORE_CASE)
            .containsMatchIn(fileName)
}
