
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

    video.setOnPreparedListener { player ->
        player.isLooping = false

        // Maintain the original video aspect ratio.
        val videoWidth = player.videoWidth
        val videoHeight = player.videoHeight

        if (videoWidth > 0 && videoHeight > 0) {
            val displayWidth = root.width
            val displayHeight = root.height

            if (displayWidth > 0 && displayHeight > 0) {
                val scale = minOf(
                    displayWidth.toFloat() / videoWidth,
                    displayHeight.toFloat() / videoHeight
                )

                video.layoutParams = FrameLayout.LayoutParams(
                    (videoWidth * scale).toInt(),
                    (videoHeight * scale).toInt(),
                    Gravity.CENTER
                )
            }
        }

        video.start()
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
        video.start()
    }
}
