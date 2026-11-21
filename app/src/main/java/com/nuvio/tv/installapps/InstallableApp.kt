package com.nuvio.tv.installapps

import org.json.JSONObject

/** An app listed in the Kable Killers store catalog (apps.json) that can be installed from inside the app. */
data class InstallableApp(
    val id: String,
    val name: String,
    val description: String,
    val apkUrl: String,
    val packageName: String? = null
)

data class InstallAppsUiState(
    val isLoading: Boolean = false,
    val apps: List<InstallableApp> = emptyList(),
    val loadFailed: Boolean = false,
    /** Id of the app currently downloading, or [URL_INSTALL_ID] for a link install. */
    val activeDownloadId: String? = null,
    val downloadProgress: Float? = null,
    val showUnknownSourcesDialog: Boolean = false,
    val message: String? = null
)

const val URL_INSTALL_ID = "__url__"

/** Store catalog read by both the TV and Mobile apps. */
const val INSTALL_APPS_CATALOG_URL =
    "https://raw.githubusercontent.com/kablekillers/Kable-Killers-Store/main/apps.json"

object InstallAppsCatalog {

    /**
     * Parses the store catalog. Only entries whose "platforms" list contains [platform]
     * (or that have no platforms list) and that have an http(s) apkUrl are returned.
     */
    fun parse(json: String, platform: String): List<InstallableApp> {
        val apps = JSONObject(json).optJSONArray("apps") ?: return emptyList()
        return (0 until apps.length()).mapNotNull { index ->
            val item = apps.optJSONObject(index) ?: return@mapNotNull null
            val platforms = item.optJSONArray("platforms")
            if (platforms != null) {
                val names = (0 until platforms.length()).map { platforms.optString(it).lowercase() }
                if (platform.lowercase() !in names) return@mapNotNull null
            }
            val name = item.optString("name").trim()
            val apkUrl = item.optString("apkUrl").trim()
            if (name.isEmpty() || normalizeApkUrl(apkUrl) == null) return@mapNotNull null
            InstallableApp(
                id = item.optString("id").trim().ifEmpty { name },
                name = name,
                description = item.optString("description").trim(),
                apkUrl = apkUrl,
                packageName = item.optString("packageName").trim().ifEmpty { null }
            )
        }
    }

    /** Returns an http(s) URL, adding https:// when no scheme was typed, or null if it isn't a usable link. */
    fun normalizeApkUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val lower = withScheme.lowercase()
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) return null
        val host = withScheme.substringAfter("://").substringBefore('/').substringBefore('?')
        if (host.isEmpty() || !host.contains('.')) return null
        return withScheme
    }
}
