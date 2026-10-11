
package com.nuvio.tv

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.activity.ComponentActivity

/**
 * Plays the Kable Killers startup video over the existing TV UI.
 * The underlying app remains intact while the intro is playing.
 */
fun ComponentActivity.playKableKillersIntro() {
    val root = window.decorView.findViewById<ViewGroup>(
        android.R.id.content
    )

    val overlay = FrameLayout(this).apply {
        setBackgroundColor(android.graphics.Color.BLACK)
        isClickable = true
        isFocusable = true
    }

    val player = VideoView(this).apply {
        setBackgroundColor(android.graphics.Color.BLACK)
    }

    overlay.addView(
        player,
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    )

    root.addView(
        overlay,
        ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    )

    var finished = false

    fun finishIntro() {
        if (finished) return
        finished = true

        player.stopPlayback()
        root.removeView(overlay)
    }

    player.setOnCompletionListener {
        finishIntro()
    }

    player.setOnErrorListener { _, _, _ ->
        finishIntro()
        true
    }

    player.setVideoURI(
        Uri.parse(
            "android.resource://$packageName/${R.raw.kable_killers_intro}"
        )
    )

    player.setOnPreparedListener {
        it.isLooping = false
        player.start()
    }
}
