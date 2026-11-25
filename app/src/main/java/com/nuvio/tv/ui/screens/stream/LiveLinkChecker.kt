package com.nuvio.tv.ui.screens.stream

import androidx.compose.runtime.mutableStateMapOf
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.screens.player.PlayerPlaybackNetworking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URI
import java.net.UnknownHostException

internal enum class LinkHealth { Checking, Ok, Dead }

/**
 * Same behaviour as Kable Killers Mobile: live sports / TV links are listed right
 * away and tested in the background; only links that are clearly dead twice in a
 * row are hidden (and never all of them). Results are kept for 10 minutes.
 * Torrent, debrid, YouTube and external-app links aren't tested.
 */
internal object LiveLinkChecker {
    private const val TTL_MILLIS = 10 * 60_000L
    private val liveTypes = setOf("tv", "sport", "sports", "channel", "channels", "livetv", "live", "event", "events")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = Semaphore(6)
    private val results = mutableStateMapOf<String, Pair<LinkHealth, Long>>()

    fun appliesTo(type: String?): Boolean = type?.lowercase()?.trim() in liveTypes

    private fun directUrl(stream: Stream): String? {
        if (stream.infoHash != null || stream.ytId != null || stream.externalUrl != null || stream.clientResolve != null) {
            return null
        }
        return stream.url?.trim()?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }

    private fun headersOf(stream: Stream): Map<String, String> =
        stream.behaviorHints?.proxyHeaders?.request.orEmpty()
            .filter { (key, value) -> key.isNotBlank() && value.isNotBlank() }

    private fun key(stream: Stream): String? = directUrl(stream)
        ?.let { url -> url + "|" + headersOf(stream).entries.sortedBy { it.key }.joinToString() }

    fun isTestable(stream: Stream): Boolean = directUrl(stream) != null

    /** null when the link isn't tested (always shown). */
    fun health(stream: Stream): LinkHealth? {
        val key = key(stream) ?: return null
        return results[key]?.first ?: LinkHealth.Checking
    }

    fun check(streams: List<Stream>) {
        val time = System.currentTimeMillis()
        streams.forEach { stream ->
            val key = key(stream) ?: return@forEach
            val existing = results[key]
            if (existing != null && (existing.first == LinkHealth.Checking || time - existing.second < TTL_MILLIS)) return@forEach
            results[key] = LinkHealth.Checking to time
            val url = directUrl(stream) ?: return@forEach
            val headers = headersOf(stream)
            scope.launch {
                val alive = gate.withPermit {
                    // Only a clear "dead" counts, and it has to happen twice. Slow or
                    // unclear answers count as working so a good link is never hidden.
                    val first = withTimeoutOrNull(20_000) { probe(url, headers) } ?: true
                    first || (withTimeoutOrNull(20_000) { probe(url, headers) } ?: true)
                }
                withContext(Dispatchers.Main) {
                    results[key] = (if (alive) LinkHealth.Ok else LinkHealth.Dead) to System.currentTimeMillis()
                }
            }
        }
    }

    /** One quick check of a link: false = clearly dead, true = works, null = can't tell. */
    suspend fun probeOnce(url: String, headers: Map<String, String>): Boolean? = probe(url, headers)

    private val htmlStart = Regex("""^\s*(<!doctype html|<html|<head|<body)""", RegexOption.IGNORE_CASE)
    private val mediaPath = Regex("""\.(m3u8|mp4|mkv|ts|mpd)(\?|$)""", RegexOption.IGNORE_CASE)

    /** Status codes that mean the link itself is gone, not "busy" or "needs a token". */
    private fun isGoneStatus(code: Int) = code == 404 || code == 410 || code in 500..504

    private sealed interface Probe {
        class Answer(val code: Int, val contentType: String?, val body: ByteArray) : Probe
        data object HostDown : Probe
        data object NoAnswer : Probe
    }

    /** false = clearly dead, true = works, null = can't tell (treated as working). */
    private suspend fun probe(url: String, headers: Map<String, String>): Boolean? = withContext(Dispatchers.IO) {
        when (val first = fetchStart(url, headers)) {
            Probe.HostDown -> false
            Probe.NoAnswer -> null
            is Probe.Answer -> {
                if (isGoneStatus(first.code)) return@withContext false
                if (first.code !in 200..299) return@withContext null
                val text = first.body.decodeToString()
                if (text.trimStart().startsWith("#EXTM3U")) {
                    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    val variantIndex = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
                    val variant = if (variantIndex >= 0) lines.drop(variantIndex + 1).firstOrNull { !it.startsWith("#") } else null
                    if (variant != null) {
                        val variantUrl = runCatching { URI(url).resolve(variant).toString() }.getOrNull()
                            ?: return@withContext true
                        val second = fetchStart(variantUrl, headers)
                        if (second == Probe.HostDown) return@withContext false
                        if (second is Probe.Answer && isGoneStatus(second.code)) return@withContext false
                    }
                    return@withContext true
                }
                val looksHtml = first.contentType?.contains("text/html", ignoreCase = true) == true ||
                    htmlStart.containsMatchIn(text.take(200))
                // A web page where a video file / playlist should be is an error or "offline" page.
                !(looksHtml && mediaPath.containsMatchIn(url))
            }
        }
    }

    private fun fetchStart(url: String, headers: Map<String, String>): Probe = try {
        val connection: HttpURLConnection = PlayerPlaybackNetworking.openConnection(
            url = url,
            headers = headers,
            method = "GET",
            connectTimeoutMs = 8_000,
            readTimeoutMs = 8_000,
            range = "bytes=0-16383",
        )
        try {
            val code = connection.responseCode
            val bytes = if (code in 200..299) {
                connection.inputStream.use { input ->
                    val buffer = ByteArray(16_384)
                    var read = 0
                    while (read < buffer.size) {
                        val n = input.read(buffer, read, buffer.size - read)
                        if (n <= 0) break
                        read += n
                    }
                    buffer.copyOf(read)
                }
            } else ByteArray(0)
            Probe.Answer(code, connection.contentType, bytes)
        } finally {
            connection.disconnect()
        }
    } catch (_: UnknownHostException) {
        Probe.HostDown
    } catch (_: ConnectException) {
        Probe.HostDown
    } catch (_: Exception) {
        Probe.NoAnswer
    }
}
