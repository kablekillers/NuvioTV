package com.nuvio.tv.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.domain.model.AppTheme

/** Colors of the Kable Killers "Cinema Black" look. */
object CinemaGold {
    val Light = Color(0xFFFFE08A)
    val Base = Color(0xFFE8B44A)
    val Deep = Color(0xFFB57A12)
    val Soft = Color(0xFFFFD27A)
    val Ink = Color(0xFF16110A)
    val Live = Color(0xFFFF4D5E)
    val Ember = Color(0xFFC83803)

    val brush: Brush get() = Brush.linearGradient(listOf(Light, Base, Deep))
    val barBrush: Brush get() = Brush.horizontalGradient(listOf(Deep, Soft))
}

/** True while Cinema Black is active, so its extra motion never changes other themes. */
val isCinemaBlackTheme: Boolean
    @Composable get() = LocalAppTheme.current == AppTheme.CINEMA_BLACK

/** A slow 0→1→0 drift for "Ken Burns" backdrops. Read the value only while drawing. */
@Composable
fun rememberCinemaDrift(enabled: Boolean, periodMillis: Int = 18_000): State<Float> {
    val transition = rememberInfiniteTransition(label = "cinema_drift")
    val drift = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(periodMillis, easing = LinearEasing), RepeatMode.Reverse),
        label = "cinema_drift_value"
    )
    return if (enabled) drift else remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
}

/** One light sweep across the content each time [focused] turns true. */
fun Modifier.cinemaFocusShine(focused: Boolean, enabled: Boolean = true, strength: Float = 0.22f): Modifier =
    if (!enabled) this else composed {
        val sweep = remember { Animatable(0f) }
        LaunchedEffect(focused) {
            if (focused) {
                sweep.snapTo(0f)
                sweep.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
            } else {
                sweep.snapTo(0f)
            }
        }
        drawWithContent {
            drawContent()
            val p = sweep.value
            if (p <= 0f || p >= 1f) return@drawWithContent
            val band = size.width * 0.45f
            val x = -band + (size.width + band * 2f) * p
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color.Transparent, Color.White.copy(alpha = strength), Color.Transparent),
                    start = Offset(x - band / 2f, 0f),
                    end = Offset(x + band / 2f, size.height * 0.35f)
                )
            )
        }
    }

/** A highlight that sweeps across every [periodMillis], for gold buttons. */
fun Modifier.cinemaShine(periodMillis: Int = 3_500, strength: Float = 0.5f): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "cinema_shine")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = periodMillis
                0f at 0
                1f at (periodMillis * 0.45f).toInt() using FastOutSlowInEasing
                1f at periodMillis
            }
        ),
        label = "cinema_shine_progress"
    )
    drawWithContent {
        drawContent()
        val p = progress
        if (p <= 0f || p >= 1f) return@drawWithContent
        val band = size.width * 0.45f
        val x = -band + (size.width + band * 2f) * p
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(Color.Transparent, Color.White.copy(alpha = strength), Color.Transparent),
                start = Offset(x - band / 2f, 0f),
                end = Offset(x + band / 2f, size.height * 0.35f)
            )
        )
    }
}

/** A live dot with an expanding ring. */
@Composable
fun CinemaPulseDot(color: Color = CinemaGold.Live, size: Dp = 8.dp, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "cinema_pulse")
    val ring by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_400, easing = LinearEasing)),
        label = "cinema_pulse_ring"
    )
    Box(
        modifier = modifier
            .size(size)
            .drawBehind {
                drawCircle(
                    color = color.copy(alpha = 0.7f * (1f - ring)),
                    radius = this.size.minDimension / 2f * (1f + 1.8f * ring)
                )
                drawCircle(color = color)
            }
    )
}

/** A gold ring whose bright edge orbits the content, marking the selected item. */
fun Modifier.cinemaSpinRing(enabled: Boolean, width: Dp = 3.dp, periodMillis: Int = 2_400): Modifier =
    if (!enabled) this else composed {
        val transition = rememberInfiniteTransition(label = "cinema_ring")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(periodMillis, easing = LinearEasing)),
            label = "cinema_ring_angle"
        )
        drawBehind {
            val stroke = width.toPx()
            rotate(angle) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        listOf(CinemaGold.Base, Color.Transparent, CinemaGold.Light, Color.Transparent, CinemaGold.Base)
                    ),
                    radius = size.minDimension / 2f - stroke / 2f,
                    style = Stroke(stroke)
                )
            }
        }
    }

/** Repeating breathing value between 0.55 and 1, for background glows. */
@Composable
fun rememberCinemaBreath(periodMillis: Int = 5_000, delayMillis: Int = 0): State<Float> {
    val transition = rememberInfiniteTransition(label = "cinema_breath")
    return transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(periodMillis / 2, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
            StartOffset(delayMillis)
        ),
        label = "cinema_breath_value"
    )
}

/** Pill with a pulsing dot, e.g. "3 LIVE". */
@Composable
fun CinemaLivePill(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(CinemaGold.Live.copy(alpha = 0.14f))
            .padding(horizontal = 14.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CinemaPulseDot()
        Text(text = text, color = Color(0xFFFF8A94), fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
    }
}
