package com.nuvio.tv.ui.components

import androidx.compose.foundation.Image
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Kable Killers branding for the navigation sidebar.
 * An explicit drawable override remains supported for callers needing a custom mark.
 */
@Composable
fun BrandWordmark(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = 1f,
    drawableOverride: Int? = null
) {
    if (drawableOverride != null) {
        Image(
            painter = painterResource(id = drawableOverride),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
            alpha = alpha
        )
    } else {
        Text(
            text = "KABLE KILLERS",
            modifier = modifier,
            color = NuvioTheme.colors.TextPrimary.copy(alpha = alpha),
            fontSize = 19.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
