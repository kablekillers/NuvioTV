
package com.nuvio.tv

import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.activity.ComponentActivity

fun ComponentActivity.playKableKillersIntro() {

    val root = window.decorView.findViewById<FrameLayout>(
        android.R.id.content
    )

    val overlay = FrameLayout(this).apply {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        isFocusable = true
        elevation = 1000f
    }

    val video = VideoView(this)

    overlay.addView(
        video,
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        )
    )

    var finished = false

    fun closeIntro() {
        if (finished) return
        finished = true
        video.stopPlayback()
        (overlay.parent as? ViewGroup)?.removeView(overlay)
    }

    // Black cover over the video until real frames are on screen. Without it the first
    // frames showed up cropped/offset while the surface was still being sized.
    val cover = android.view.View(this).apply { setBackgroundColor(Color.BLACK) }
    overlay.addView(
        cover,
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    )

    video.setOnPreparedListener { player ->
        player.isLooping = false
        player.setOnInfoListener { _, what, _ ->
            if (what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                // One extra frame so the correctly sized frame is the first one shown.
                cover.postDelayed({ cover.visibility = android.view.View.GONE }, 50)
            }
            false
        }
        // VideoView already keeps the video's aspect ratio inside MATCH_PARENT,
        // so no manual resize (resizing mid-playback caused the offset frame).
        video.start()
        // Safety net for decoders that never report rendering start.
        cover.postDelayed({ cover.visibility = android.view.View.GONE }, 1500)
    }

    video.setOnCompletionListener {
        closeIntro()
    }

    video.setOnErrorListener { _, _, _ ->
        closeIntro()
        true
    }

    video.setVideoURI(
        Uri.parse(
            "android.resource://$packageName/${R.raw.kable_killers_intro}"
        )
    )

    // Wait until the existing app has created its screen.
    // Then place the intro above everything else.
    root.post {
        root.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        overlay.bringToFront()
    }
}
