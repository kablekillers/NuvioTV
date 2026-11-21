package com.nuvio.tv.ui.screens.music

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Official YouTube video in a dedicated, hardware-accelerated Android TV window. */
class YouTubeVideoActivity : Activity() {
    private var player: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        window.statusBarColor = Color.rgb(7, 15, 32)
        window.navigationBarColor = Color.rgb(7, 15, 32)
        val id = intent.getStringExtra("video_id").orEmpty()
        if (!Regex("^[a-zA-Z0-9_-]{11}$").matches(id)) {
            finish()
            return
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(12))
            setBackgroundColor(Color.rgb(7, 15, 32))
        }
        root.addView(TextView(this).apply {
            text = intent.getStringExtra("title").orEmpty()
            textSize = 22f
            setTextColor(Color.WHITE)
            maxLines = 2
        }, LinearLayout.LayoutParams(-1, -2))
        player = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            setLayerType(View.LAYER_TYPE_NONE, null)
            isFocusable = true
            isFocusableInTouchMode = true
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            loadUrl(
                "https://www.youtube.com/embed/$id?playsinline=1",
                mapOf("Referer" to "https://kablekillers.github.io/")
            )
        }
        root.addView(player, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(12)
        })
        root.addView(Button(this).apply {
            text = "Close video"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-2, -2))
        setContentView(root)
    }

    override fun onPause() {
        player?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        player?.onResume()
    }

    override fun onDestroy() {
        player?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.stopLoading()
            it.loadUrl("about:blank")
            it.destroy()
        }
        player = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        fun intentFor(context: Context, result: MusicResult): Intent =
            Intent(context, YouTubeVideoActivity::class.java).apply {
                putExtra("video_id", result.id)
                putExtra("title", result.title)
            }
    }
}
