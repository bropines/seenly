package io.github.bropines.lens.app.ui.gallery

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * A soft band of light crossing a placeholder, so something that is loading
 * reads as loading rather than as empty. Drawn only: no recomposition per frame.
 */
@Composable
fun Modifier.shimmer(
    base: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    highlight: Color = MaterialTheme.colorScheme.surfaceBright,
): Modifier {
    val phase by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer",
    )
    return drawBehind {
        drawRect(base)
        // A slanted band about half the width, starting off one side and leaving by the other.
        val band = size.width * 0.6f
        val x = -band + (size.width + 2 * band) * phase
        drawRect(
            Brush.linearGradient(
                colors = listOf(Color.Transparent, highlight.copy(alpha = 0.55f), Color.Transparent),
                start = Offset(x - band, 0f),
                end = Offset(x, size.height * 0.6f),
            ),
        )
    }
}
