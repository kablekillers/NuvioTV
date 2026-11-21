package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// ---------------------------------------------------------------------------
// Kable Killers TV player extras: night mode, Sync by ear and seek thumbnails.
// ---------------------------------------------------------------------------

/** Night mode strengths: how far quiet sounds and dialogue are lifted. */
internal enum class NightModeLevel(val label: String, val maxBoostDb: Float, val mpvFilter: String) {
    Off("Off", 0f, ""),
    Low("Low", 6f, "lavfi=[acompressor=threshold=-24dB:ratio=2:attack=5:release=300:makeup=2dB,alimiter=limit=0.95]"),
    Medium("Medium", 10f, "lavfi=[acompressor=threshold=-28dB:ratio=3:attack=5:release=300:makeup=4dB,alimiter=limit=0.95]"),
    High("High", 15f, "lavfi=[acompressor=threshold=-32dB:ratio=4:attack=5:release=300:makeup=6dB,alimiter=limit=0.95]");

    fun next(): NightModeLevel = entries[(ordinal + 1) % entries.size]
}

internal object TvPlayerExtras {
    private const val PREFS = "kk_tv_player_extras"
    private const val KEY_NIGHT = "night_mode_level"
    private const val KEY_THUMBS = "seek_thumbnails"
    private var prefs: SharedPreferences? = null

    private val _nightMode = MutableStateFlow(NightModeLevel.Off)
    val nightMode: StateFlow<NightModeLevel> = _nightMode.asStateFlow()

    private val _seekThumbnails = MutableStateFlow(true)
    val seekThumbnails: StateFlow<Boolean> = _seekThumbnails.asStateFlow()

    private val _syncByEarOpen = MutableStateFlow(false)
    val syncByEarOpen: StateFlow<Boolean> = _syncByEarOpen.asStateFlow()

    /** Read by the audio processor on the audio thread. */
    @Volatile
    var nightModeNow: NightModeLevel = NightModeLevel.Off
        private set

    fun initialize(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { p ->
            val level = runCatching { NightModeLevel.valueOf(p.getString(KEY_NIGHT, null) ?: "Off") }
                .getOrDefault(NightModeLevel.Off)
            _nightMode.value = level
            nightModeNow = level
            _seekThumbnails.value = p.getBoolean(KEY_THUMBS, true)
        }
    }

    fun setNightMode(level: NightModeLevel) {
        _nightMode.value = level
        nightModeNow = level
        prefs?.edit()?.putString(KEY_NIGHT, level.name)?.apply()
    }

    fun setSeekThumbnails(enabled: Boolean) {
        _seekThumbnails.value = enabled
        prefs?.edit()?.putBoolean(KEY_THUMBS, enabled)?.apply()
    }

    fun openSyncByEar() { _syncByEarOpen.value = true }
    fun closeSyncByEar() { _syncByEarOpen.value = false }
}

/**
 * Night mode for ExoPlayer: upward compression. Quiet sounds and dialogue are lifted by up to the
 * level's boost, loud scenes stay as they were mixed, and a soft limiter keeps peaks from clipping.
 * Always in the chain for PCM (it only copies while off) so switching never reconfigures the sink.
 */
internal class TvNightModeAudioProcessor : BaseAudioProcessor() {
    private var envelopeDb = -90f
    private var smoothedGainDb = 0f
    private var attack = 0f
    private var release = 0f
    private var channels = 2

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> {
                val rate = inputAudioFormat.sampleRate.coerceAtLeast(8_000).toFloat()
                channels = inputAudioFormat.channelCount.coerceAtLeast(1)
                attack = exp(-1f / (rate * 0.010f))
                release = exp(-1f / (rate * 0.400f))
                envelopeDb = -90f
                smoothedGainDb = 0f
                inputAudioFormat
            }
            else -> AudioProcessor.AudioFormat.NOT_SET
        }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val out = replaceOutputBuffer(inputBuffer.remaining())
        val level = TvPlayerExtras.nightModeNow
        if (level == NightModeLevel.Off) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        inputBuffer.order(ByteOrder.nativeOrder())
        out.order(ByteOrder.nativeOrder())
        val frame = FloatArray(channels)
        val pcm16 = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
        val frameBytes = (if (pcm16) 2 else 4) * channels
        while (inputBuffer.remaining() >= frameBytes) {
            for (c in 0 until channels) frame[c] = if (pcm16) inputBuffer.short / 32768f else inputBuffer.float
            val gain = frameGain(frame, level.maxBoostDb)
            for (c in 0 until channels) {
                val v = softLimit(frame[c] * gain)
                if (pcm16) out.putShort((v * 32767f).toInt().toShort()) else out.putFloat(v)
            }
        }
        if (inputBuffer.hasRemaining()) out.put(inputBuffer)
        out.flip()
    }

    private fun frameGain(frame: FloatArray, maxBoostDb: Float): Float {
        var peak = 0f
        for (v in frame) peak = max(peak, abs(v))
        val levelDb = if (peak > 1e-6f) 20f * log10(peak) else -120f
        val coeff = if (levelDb > envelopeDb) attack else release
        envelopeDb = coeff * envelopeDb + (1f - coeff) * levelDb
        // Below the threshold the sound is lifted toward it; near-silence (noise) is left alone.
        val targetDb = when {
            envelopeDb < GATE_DB -> 0f
            envelopeDb < THRESHOLD_DB -> min(maxBoostDb, (THRESHOLD_DB - envelopeDb) * 0.6f)
            else -> 0f
        }
        smoothedGainDb += (targetDb - smoothedGainDb) * 0.0015f
        return 10f.pow(smoothedGainDb / 20f)
    }

    private fun softLimit(v: Float): Float {
        val a = abs(v)
        if (a <= 0.9f) return v
        val limited = 0.9f + (1f - 0.9f) * (1f - exp(-(a - 0.9f) / 0.1f))
        return if (v < 0) -limited else limited
    }

    override fun onReset() {
        envelopeDb = -90f
        smoothedGainDb = 0f
    }

    private companion object {
        const val THRESHOLD_DB = -18f
        const val GATE_DB = -60f
    }
}

// --- Seek thumbnails --------------------------------------------------------

/** Frames for the seek preview, read with MediaMetadataRetriever (progressive files only). */
internal class TvSeekThumbnailLoader(private val url: String, private val headers: Map<String, String>) {
    private val mutex = Mutex()
    private var retriever: MediaMetadataRetriever? = null
    private var failed = false
    private val cache = LinkedHashMap<Long, ImageBitmap>(32, 0.75f, true)

    suspend fun frameAt(positionMs: Long): ImageBitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (failed) return@withLock null
            val bucket = positionMs / BUCKET_MS
            cache[bucket]?.let { return@withLock it }
            val mmr = retriever ?: open() ?: return@withLock null
            val bitmap = runCatching {
                val timeUs = bucket * BUCKET_MS * 1000L
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    mmr.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 384, 216)
                } else {
                    mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?.let { Bitmap.createScaledBitmap(it, 384, 216, true) }
                }
            }.getOrNull() ?: return@withLock null
            val image = bitmap.asImageBitmap()
            cache[bucket] = image
            if (cache.size > 80) cache.remove(cache.keys.first())
            image
        }
    }

    private fun open(): MediaMetadataRetriever? {
        val supported = url.startsWith("http://") || url.startsWith("https://") || url.startsWith("/") || url.startsWith("file:")
        val adaptive = url.contains(".m3u8", true) || url.contains(".mpd", true) || url.startsWith("magnet:")
        if (!supported || adaptive) { failed = true; return null }
        return runCatching {
            MediaMetadataRetriever().apply {
                when {
                    url.startsWith("file:") -> setDataSource(url.removePrefix("file://"))
                    url.startsWith("/") -> setDataSource(url)
                    else -> setDataSource(url, headers)
                }
            }
        }.onFailure { failed = true }.getOrNull()?.also { retriever = it }
    }

    fun release() {
        runCatching { retriever?.release() }
        retriever = null
        cache.clear()
    }

    private companion object { const val BUCKET_MS = 5_000L }
}

/** A still of the video above the seek bar while fast-forwarding or rewinding. */
@Composable
internal fun SeekThumbnailPreview(viewModel: PlayerViewModel, modifier: Modifier = Modifier) {
    val enabled by TvPlayerExtras.seekThumbnails.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val timeline by viewModel.playbackTimeline.collectAsState()
    val previewAt = uiState.pendingPreviewSeekPosition
    val streamUrl = uiState.currentStreamUrl ?: viewModel.getCurrentStreamUrl()
    if (!enabled || timeline.isLive) return
    val loader = remember(streamUrl) { TvSeekThumbnailLoader(streamUrl, viewModel.getCurrentHeaders()) }
    DisposableEffect(loader) { onDispose { loader.release() } }
    var frame by remember(streamUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(previewAt?.let { it / 5_000L }) {
        if (previewAt == null) { frame = null; return@LaunchedEffect }
        delay(80)
        loader.frameAt(previewAt)?.let { frame = it }
    }
    val image = frame
    if (previewAt == null || image == null || timeline.duration <= 0L) return
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = 256.dp
        val fraction = (previewAt.toFloat() / timeline.duration).coerceIn(0f, 1f)
        val x = (maxWidth * fraction - width / 2).coerceIn(0.dp, (maxWidth - width).coerceAtLeast(0.dp))
        Column(Modifier.offset(x = x), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = width, height = 144.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(2.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(10.dp)),
            )
        }
    }
}

// --- Sync by ear --------------------------------------------------------------

/**
 * Sync by ear: press "Heard" when you hear a line and "Saw" when its subtitle appears, in either
 * order. The gap between the two presses becomes the subtitle delay.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SyncByEarOverlay(viewModel: PlayerViewModel) {
    val open by TvPlayerExtras.syncByEarOpen.collectAsState()
    if (!open) return
    val uiState by viewModel.uiState.collectAsState()
    val timeline by viewModel.playbackTimeline.collectAsState()
    var heardAt by remember { mutableStateOf<Long?>(null) }
    var sawAt by remember { mutableStateOf<Long?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    val heardFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { heardFocus.requestFocus() } }

    fun finish() {
        val h = heardAt ?: return
        val s = sawAt ?: return
        val newDelay = (uiState.subtitleDelayMs + (h - s).toInt())
        viewModel.onEvent(PlayerEvent.OnAdjustSubtitleDelay(newDelay - uiState.subtitleDelayMs, showOverlay = false))
        result = if (newDelay == 0) "Now in sync" else "Subtitle delay set to ${"%+.1f".format(newDelay / 1000f)}s"
        heardAt = null
        sawAt = null
    }

    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_UP) TvPlayerExtras.closeSyncByEar()
                true
            } else false
        },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .padding(bottom = 60.dp)
                .width(560.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color.Black.copy(alpha = 0.82f))
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Sync by ear", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                text = result ?: when {
                    heardAt != null -> "Heard captured. Press Saw when its subtitle shows."
                    sawAt != null -> "Saw captured. Press Heard when you hear the line."
                    else -> "Press Heard when you hear a line and Saw when its subtitle shows, in either order."
                },
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                fontSize = 15.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SyncByEarCard("Heard", heardAt != null, Modifier.focusRequester(heardFocus)) {
                    result = null
                    heardAt = timeline.currentPosition
                    finish()
                }
                SyncByEarCard("Saw", sawAt != null) {
                    result = null
                    sawAt = timeline.currentPosition
                    finish()
                }
                SyncByEarCard("Done", false) { TvPlayerExtras.closeSyncByEar() }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SyncByEarCard(label: String, captured: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier.width(150.dp),
        colors = CardDefaults.colors(
            containerColor = if (captured) NuvioTheme.colors.Secondary else Color.White.copy(alpha = 0.12f),
            focusedContainerColor = if (captured) NuvioTheme.colors.Secondary else Color.White.copy(alpha = 0.22f),
        ),
        shape = CardDefaults.shape(RoundedCornerShape(16.dp)),
        border = CardDefaults.border(
            focusedBorder = Border(border = BorderStroke(3.dp, Color.White), shape = RoundedCornerShape(16.dp)),
        ),
        scale = CardDefaults.scale(focusedScale = 1.04f),
    ) {
        Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
            Text(label, color = if (captured) NuvioTheme.colors.OnSecondary else Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}
