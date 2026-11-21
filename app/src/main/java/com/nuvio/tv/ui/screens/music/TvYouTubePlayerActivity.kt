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

/**
 * Full-screen YouTube player for TV using YouTube's official IFrame player. The remote controls
 * it: OK / Play-Pause toggles, left and right skip 10 seconds, Back closes.
 */
class TvYouTubePlayerActivity : Activity() {
    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID).orEmpty()
        if (!Regex("^[a-zA-Z0-9_-]{11}$").matches(videoId)) {
            finish()
            return
        }
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
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
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
        webView?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onDestroy() {
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

        fun start(context: Context, videoId: String) {
            context.startActivity(
                Intent(context, TvYouTubePlayerActivity::class.java)
                    .putExtra(EXTRA_VIDEO_ID, videoId)
                    .addFlags(if (context is Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
