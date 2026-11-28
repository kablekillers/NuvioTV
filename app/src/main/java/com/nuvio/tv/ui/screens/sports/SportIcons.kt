package com.nuvio.tv.ui.screens.sports

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Original Kable Killers sport icons (not league trademarks), drawn in code so they
 * stay sharp at any size and tint with the electric-blue theme.
 */
enum class SportGlyph { FOOTBALL, COLLEGE_FOOTBALL, BASKETBALL, BASEBALL, HOCKEY, MMA, TENNIS, GOLF, RACING, SOCCER, TARGET, OTHER }

fun SportsLeague.glyph(): SportGlyph = when (this) {
    SportsLeague.NFL -> SportGlyph.FOOTBALL
    SportsLeague.COLLEGE_FOOTBALL -> SportGlyph.COLLEGE_FOOTBALL
    SportsLeague.NBA, SportsLeague.COLLEGE_BASKETBALL, SportsLeague.WNBA -> SportGlyph.BASKETBALL
    SportsLeague.MLB, SportsLeague.COLLEGE_BASEBALL -> SportGlyph.BASEBALL
    SportsLeague.NHL, SportsLeague.COLLEGE_HOCKEY -> SportGlyph.HOCKEY
    SportsLeague.UFC, SportsLeague.WWE -> SportGlyph.MMA
    SportsLeague.TENNIS -> SportGlyph.TENNIS
    SportsLeague.PGA, SportsLeague.LPGA -> SportGlyph.GOLF
    SportsLeague.FORMULA_1, SportsLeague.NASCAR, SportsLeague.INDYCAR -> SportGlyph.RACING
    SportsLeague.MLS -> SportGlyph.SOCCER
    SportsLeague.COLLEGE_VOLLEYBALL -> SportGlyph.OTHER
}

@Composable
fun SportIcon(glyph: SportGlyph, modifier: Modifier = Modifier, size: Dp = 26.dp, color: Color = Color.White) {
    Canvas(modifier = modifier.size(size)) {
        val stroke = Stroke(width = this.size.minDimension * 0.08f, cap = StrokeCap.Round)
        when (glyph) {
            SportGlyph.FOOTBALL -> drawFootball(color, stroke)
            SportGlyph.COLLEGE_FOOTBALL -> drawCollegeFootball(color, stroke)
            SportGlyph.BASKETBALL -> drawBasketball(color, stroke)
            SportGlyph.BASEBALL -> drawBaseball(color, stroke)
            SportGlyph.HOCKEY -> drawHockey(color, stroke)
            SportGlyph.MMA -> drawGlove(color, stroke)
            SportGlyph.TENNIS -> {
                val racquet = ballRect(0.52f, 0.62f)
                drawOval(color, racquet.topLeft - Offset(0f, this.size.height * 0.14f), racquet.size, style = stroke)
                drawLine(color, Offset(center.x, this.size.height * 0.67f), Offset(center.x, this.size.height * 0.94f), stroke.width)
            }
            SportGlyph.GOLF -> {
                drawLine(color, Offset(center.x, this.size.height * 0.86f), Offset(center.x, this.size.height * 0.14f), stroke.width)
                drawLine(color, Offset(center.x, this.size.height * 0.14f), Offset(this.size.width * 0.84f, this.size.height * 0.31f), stroke.width)
                drawLine(color, Offset(this.size.width * 0.84f, this.size.height * 0.31f), Offset(center.x, this.size.height * 0.49f), stroke.width)
                drawOval(color, Offset(this.size.width * 0.23f, this.size.height * 0.79f), Size(this.size.width * 0.54f, this.size.height * 0.16f), style = stroke)
            }
            SportGlyph.RACING -> {
                val cell = this.size.minDimension * 0.20f
                drawLine(color, Offset(cell, cell * 0.6f), Offset(cell, cell * 4.7f), stroke.width)
                for (row in 0..2) for (column in 0..2) {
                    if ((row + column) % 2 == 0) {
                        drawRect(color, Offset((column + 1) * cell, (row + 1) * cell), Size(cell, cell))
                    }
                }
            }
            SportGlyph.SOCCER, SportGlyph.TARGET, SportGlyph.OTHER -> {
                drawCircle(color, radius = this.size.minDimension * 0.42f, style = stroke)
                drawCircle(color, radius = this.size.minDimension * 0.19f, style = stroke)
            }
        }
    }
}

/** Header badge: the sports grouped as overlapping electric-blue medallions. */
@Composable
fun SportsHeaderBadge(modifier: Modifier = Modifier, accent: Color, background: Color) {
    val glyphs = listOf(
        SportGlyph.FOOTBALL, SportGlyph.BASKETBALL, SportGlyph.BASEBALL,
        SportGlyph.HOCKEY, SportGlyph.MMA, SportGlyph.COLLEGE_FOOTBALL
    )
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        glyphs.forEachIndexed { index, glyph ->
            Box(
                modifier = Modifier
                    .offset(x = (-10 * index).dp)
                    .size(44.dp)
                    .background(background, CircleShape)
                    .border(2.dp, accent, CircleShape)
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                SportIcon(glyph, size = 28.dp, color = if (index % 2 == 0) Color.White else accent)
            }
        }
    }
}

private fun DrawScope.ballRect(widthFraction: Float, heightFraction: Float): Rect {
    val w = size.width * widthFraction
    val h = size.height * heightFraction
    return Rect(Offset((size.width - w) / 2f, (size.height - h) / 2f), Size(w, h))
}

private fun DrawScope.drawFootball(color: Color, stroke: Stroke) {
    rotate(-35f) {
        val r = ballRect(0.92f, 0.56f)
        drawOval(color, r.topLeft, r.size, style = stroke)
        val cy = center.y
        drawLine(color, Offset(center.x - r.width * 0.22f, cy), Offset(center.x + r.width * 0.22f, cy), stroke.width * 0.8f, StrokeCap.Round)
        for (i in -2..2) {
            val x = center.x + i * r.width * 0.09f
            drawLine(color, Offset(x, cy - r.height * 0.13f), Offset(x, cy + r.height * 0.13f), stroke.width * 0.7f, StrokeCap.Round)
        }
    }
}

private fun DrawScope.drawCollegeFootball(color: Color, stroke: Stroke) {
    // Football with the classic college shoulder stripes near each tip.
    rotate(-35f) {
        val r = ballRect(0.92f, 0.56f)
        drawOval(color, r.topLeft, r.size, style = stroke)
        for (side in listOf(-1f, 1f)) {
            val x = center.x + side * r.width * 0.30f
            drawLine(color, Offset(x, center.y - r.height * 0.34f), Offset(x, center.y + r.height * 0.34f), stroke.width * 0.9f, StrokeCap.Round)
        }
        drawLine(color, Offset(center.x - r.width * 0.12f, center.y), Offset(center.x + r.width * 0.12f, center.y), stroke.width * 0.8f, StrokeCap.Round)
        for (i in -1..1) {
            val x = center.x + i * r.width * 0.07f
            drawLine(color, Offset(x, center.y - r.height * 0.11f), Offset(x, center.y + r.height * 0.11f), stroke.width * 0.7f, StrokeCap.Round)
        }
    }
}

private fun DrawScope.drawBasketball(color: Color, stroke: Stroke) {
    val radius = size.minDimension * 0.44f
    drawCircle(color, radius, center, style = stroke)
    drawLine(color, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), stroke.width)
    drawLine(color, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), stroke.width)
    val arc = Size(radius * 1.3f, radius * 2f)
    drawArc(color, -60f, 120f, false, Offset(center.x - radius * 1.55f, center.y - radius), arc, style = stroke)
    drawArc(color, 120f, 120f, false, Offset(center.x + radius * 0.25f, center.y - radius), arc, style = stroke)
}

private fun DrawScope.drawBaseball(color: Color, stroke: Stroke) {
    val radius = size.minDimension * 0.44f
    drawCircle(color, radius, center, style = stroke)
    val arc = Size(radius * 1.2f, radius * 1.7f)
    val top = center.y - radius * 0.85f
    drawArc(color, -55f, 110f, false, Offset(center.x - radius * 1.25f, top), arc, style = stroke)
    drawArc(color, 125f, 110f, false, Offset(center.x + radius * 0.05f, top), arc, style = stroke)
    // Stitches
    for (side in listOf(-1f, 1f)) {
        for (i in -1..1) {
            val y = center.y + i * radius * 0.35f
            val x = center.x + side * radius * 0.52f
            drawLine(color, Offset(x - radius * 0.12f, y), Offset(x + radius * 0.12f, y), stroke.width * 0.6f, StrokeCap.Round)
        }
    }
}

private fun DrawScope.drawHockey(color: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    // Stick
    val stick = Path().apply {
        moveTo(w * 0.72f, h * 0.06f)
        lineTo(w * 0.40f, h * 0.74f)
        quadraticBezierTo(w * 0.36f, h * 0.84f, w * 0.24f, h * 0.84f)
        lineTo(w * 0.06f, h * 0.84f)
    }
    drawPath(stick, color, style = stroke)
    // Puck
    val puck = Rect(Offset(w * 0.56f, h * 0.70f), Size(w * 0.36f, h * 0.16f))
    drawOval(color, puck.topLeft, puck.size)
}

private fun DrawScope.drawGlove(color: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    // Fist
    drawRoundRect(color, Offset(w * 0.22f, h * 0.12f), Size(w * 0.58f, h * 0.50f), CornerRadius(w * 0.22f), style = stroke)
    // Thumb
    drawRoundRect(color, Offset(w * 0.10f, h * 0.36f), Size(w * 0.28f, h * 0.20f), CornerRadius(w * 0.10f), style = stroke)
    // Knuckle line
    drawLine(color, Offset(w * 0.40f, h * 0.30f), Offset(w * 0.70f, h * 0.30f), stroke.width * 0.7f, StrokeCap.Round)
    // Cuff
    drawRoundRect(color, Offset(w * 0.28f, h * 0.66f), Size(w * 0.46f, h * 0.22f), CornerRadius(w * 0.06f))
}
