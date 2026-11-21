package com.nuvio.tv.installapps

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.updater.ApkDownloader
import com.nuvio.tv.updater.ApkInstaller
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

@HiltViewModel
class InstallAppsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val apkDownloader: ApkDownloader
) : ViewModel() {

    private val _uiState = MutableStateFlow(InstallAppsUiState())
    val uiState: StateFlow<InstallAppsUiState> = _uiState.asStateFlow()

    /** APK that finished downloading but is waiting for the "install unknown apps" permission. */
    private var pendingApk: File? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadFailed = false) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val request = Request.Builder()
                        .url("$INSTALL_APPS_CATALOG_URL?t=${System.currentTimeMillis() / 60_000}")
                        .build()
                    okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) error("HTTP ${response.code}")
                        InstallAppsCatalog.parse(response.body?.string().orEmpty(), PLATFORM)
                    }
                }
            }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    apps = result.getOrDefault(it.apps),
                    loadFailed = result.isFailure
                )
            }
        }
    }

    fun install(app: InstallableApp) {
        downloadAndInstall(app.id, app.apkUrl)
    }

    fun installFromUrl(rawUrl: String) {
        val url = InstallAppsCatalog.normalizeApkUrl(rawUrl)
        if (url == null) {
            _uiState.update { it.copy(message = context.getString(R.string.install_apps_invalid_url)) }
            return
        }
        downloadAndInstall(URL_INSTALL_ID, url)
    }

    private fun downloadAndInstall(id: String, url: String) {
        if (_uiState.value.activeDownloadId != null) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(activeDownloadId = id, downloadProgress = 0f, message = null)
            }
            val dir = File(context.cacheDir, "installs")
            val destination = File(dir, "install-${System.currentTimeMillis()}.apk")
            val result = withContext(Dispatchers.IO) {
                dir.listFiles()?.forEach { it.delete() }
                apkDownloader.download(url, destination) { downloaded, total ->
                    val progress = if (total != null && total > 0) {
                        (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    } else {
                        null
                    }
                    _uiState.update { it.copy(downloadProgress = progress) }
                }.mapCatching { file ->
                    // A real APK is a zip file; anything else (an HTML page, an error) is rejected.
                    val header = ByteArray(2)
                    val read = file.inputStream().use { it.read(header) }
                    if (read < 2 || header[0] != 'P'.code.toByte() || header[1] != 'K'.code.toByte()) {
                        file.delete()
                        error("not an apk")
                    }
                    file
                }
            }
            _uiState.update { it.copy(activeDownloadId = null, downloadProgress = null) }
            result
                .onSuccess { file -> installOrRequestPermission(file) }
                .onFailure {
                    _uiState.update {
                        it.copy(message = context.getString(R.string.install_apps_download_failed))
                    }
                }
        }
    }

    private fun installOrRequestPermission(file: File) {
        if (!ApkInstaller.canRequestPackageInstalls(context)) {
            pendingApk = file
            _uiState.update { it.copy(showUnknownSourcesDialog = true) }
            return
        }
        pendingApk = null
        _uiState.update { it.copy(showUnknownSourcesDialog = false) }
        runCatching { ApkInstaller.launchInstall(context, file) }
            .onFailure {
                _uiState.update {
                    it.copy(message = context.getString(R.string.install_apps_install_failed))
                }
            }
    }

    /** Call when returning from the permission screen, or when the user taps "Install" again. */
    fun retryPendingInstall() {
        val file = pendingApk ?: return
        if (file.exists() && ApkInstaller.canRequestPackageInstalls(context)) {
            installOrRequestPermission(file)
        }
    }

    fun openUnknownSourcesSettings() {
        ApkInstaller.buildUnknownSourcesSettingsIntent(context)?.let(context::startActivity)
    }

    fun dismissUnknownSourcesDialog() {
        _uiState.update { it.copy(showUnknownSourcesDialog = false) }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private companion object {
        const val PLATFORM = "tv"
    }
}
