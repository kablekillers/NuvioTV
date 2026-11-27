package com.nuvio.tv.ui.screens.music

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.nuvio.tv.music.MusicStreamResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen YouTube player for TV. Videos play in the app's own player, through the same
 * YouTube engine (and YouTube sign-in) as the rest of the app: YouTube's web player asks TVs to
 * "sign in to confirm you're not a bot", and its sign-in button can't be reached with a remote.
 * The web player is only the fallback when the app's player can't get the video.
 * The remote: OK / Play-Pause toggles, left and right skip 10 seconds, Back closes.
 */
class TvYouTubePlayerActivity : Activity() {
    private var webView: WebView? = null
    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID).orEmpty()
        if (!Regex("^[a-zA-Z0-9_-]{11}$").matches(videoId)) {
            finish()
            return
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val status = TextView(this).apply {
            text = "Loading…"
            setTextColor(Color.WHITE)
            textSize = 22f
        }
        root.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
        setContentView(root)
        scope.launch {
            val stream = withContext(Dispatchers.IO) {
                runCatching { MusicStreamResolver.resolveVideo(applicationContext, videoId) }
                    .onFailure { android.util.Log.w(TAG, "native YouTube failed for $videoId", it) }
                    .getOrNull()
            }
            if (isFinishing || isDestroyed) return@launch
            if (stream == null) { showWebPlayer(videoId); return@launch }
            val selector = DefaultTrackSelector(this@TvYouTubePlayerActivity).apply {
                setParameters(buildUponParameters().setMaxVideoSize(1920, 1080).setMaxVideoBitrate(8_000_000))
            }
            val http = DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(stream.headers)
                .setUserAgent(stream.headers["User-Agent"] ?: UA)
            var started = false
            val exo = ExoPlayer.Builder(this@TvYouTubePlayerActivity)
                .setTrackSelector(selector)
                .setMediaSourceFactory(DefaultMediaSourceFactory(http))
                .build()
            exo.setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true
            )
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) { started = true; status.visibility = View.GONE }
                    if (state == Player.STATE_ENDED) finish()
                }
                override fun onPlayerError(error: PlaybackException) {
                    android.util.Log.w(TAG, "native YouTube playback failed for $videoId", error)
                    // Never played: try YouTube's web player instead. Mid-video: say so.
                    if (!started) showWebPlayer(videoId)
                    else { status.text = "This video stopped playing. Press Back and try again."; status.visibility = View.VISIBLE }
                }
            })
            player = exo
            val pv = PlayerView(this@TvYouTubePlayerActivity).apply {
                this.player = exo
                useController = true
                controllerShowTimeoutMs = 3_000
                setShowNextButton(false)
                setShowPreviousButton(false)
                isFocusable = false
            }
            playerView = pv
            root.addView(pv, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            exo.setMediaItem(
                MediaItem.Builder().setUri(stream.url).apply { stream.mimeType?.let { setMimeType(it) } }.build()
            )
            exo.prepare()
            exo.playWhenReady = true
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWebPlayer(videoId: String) {
        if (webView != null) return
        player?.release(); player = null
        playerView?.let { (it.parent as? ViewGroup)?.removeView(it) }; playerView = null
        val view = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            isFocusable = false
            loadDataWithBaseURL("https://kablekillers.github.io/", playerHtml(videoId), "text/html", "utf-8", null)
        }
        webView = view
        setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        player?.let { p ->
            val handled = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_SPACE -> { if (p.isPlaying) p.pause() else p.play(); true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { p.play(); true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { p.pause(); true }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { p.seekTo(p.currentPosition + 10_000); true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0)); true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO -> true
                else -> false
            }
            if (handled) { playerView?.showController(); return true }
            return super.dispatchKeyEvent(event)
        }
        val js = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_SPACE -> "kkToggle()"
            KeyEvent.KEYCODE_MEDIA_PLAY -> "player.playVideo()"
            KeyEvent.KEYCODE_MEDIA_PAUSE -> "player.pauseVideo()"
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "kkSeek(10)"
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> "kkSeek(-10)"
            else -> null
        }
        if (js != null) {
            webView?.evaluateJavascript("try{$js}catch(e){}", null)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        player?.pause()
        webView?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onDestroy() {
        scope.cancel()
        player?.release()
        player = null
        webView?.let { v ->
            (v.parent as? ViewGroup)?.removeView(v)
            v.stopLoading()
            v.loadUrl("about:blank")
            v.destroy()
        }
        webView = null
        super.onDestroy()
    }

    private fun playerHtml(videoId: String) = """
        <!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{width:100%;height:100%}</style></head>
        <body><div id="p"></div>
        <script src="https://www.youtube.com/iframe_api"></script>
        <script>
          var player;
          function onYouTubeIframeAPIReady(){
            player = new YT.Player('p',{videoId:'$videoId',width:'100%',height:'100%',
              playerVars:{autoplay:1,playsinline:1,rel:0,modestbranding:1,controls:1},
              events:{onReady:function(e){e.target.playVideo();}}});
          }
          function kkToggle(){ if(player.getPlayerState()==1){player.pauseVideo();}else{player.playVideo();} }
          function kkSeek(s){ player.seekTo(Math.max(0,player.getCurrentTime()+s),true); }
        </script></body></html>
    """.trimIndent()

    companion object {
        private const val EXTRA_VIDEO_ID = "video_id"
        private const val TAG = "TvYouTubePlayer"
        private const val UA = "Mozilla/5.0 (Linux; Android 13; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

        fun start(context: Context, videoId: String) {
            context.startActivity(
                Intent(context, TvYouTubePlayerActivity::class.java)
                    .putExtra(EXTRA_VIDEO_ID, videoId)
                    .addFlags(if (context is Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
