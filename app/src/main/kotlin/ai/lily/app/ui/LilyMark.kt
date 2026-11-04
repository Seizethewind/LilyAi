package ai.lily.app.ui

import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** True when the person has turned animations off in the system settings. Moving things then hold still. */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

/** Five petals around a gold stamen. [alphaOf] gives each petal's opacity, so the same drawing is the logo and the progress mark. */
private fun DrawScope.bloom(petal: Color, stamen: Color, alphaOf: (Int) -> Float) {
    val centre = Offset(size.width / 2f, size.height / 2f)
    val length = size.minDimension * 0.46f
    val width = size.minDimension * 0.19f
    val shape = Path().apply {
        moveTo(0f, 0f)
        cubicTo(-width, -length * 0.30f, -width * 0.9f, -length * 0.85f, 0f, -length)
        cubicTo(width * 0.9f, -length * 0.85f, width, -length * 0.30f, 0f, 0f)
        close()
    }
    for (i in 0 until 5) {
        withTransform({
            translate(centre.x, centre.y)
            rotate(i * 72f, Offset.Zero)
        }) {
            drawPath(shape, petal.copy(alpha = alphaOf(i)))
        }
    }
    drawCircle(stamen, radius = size.minDimension * 0.07f, center = centre)
}

/** The Lily mark: a still five-petal bloom. Decorative, so it has no description. */
@Composable
fun LilyMark(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    petal: Color = MaterialTheme.colorScheme.primary,
    stamen: Color = LilyUi.colors.stamen,
) {
    Canvas(modifier.size(size)) { bloom(petal, stamen) { 0.92f } }
}

/**
 * The same bloom while Lily works: the petals light up one after another. Under reduced motion it is the still mark.
 * Pair it with words ("Lily is working") so the state is not carried by the animation alone.
 */
@Composable
fun LilyBloom(
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    petal: Color = MaterialTheme.colorScheme.primary,
    stamen: Color = LilyUi.colors.stamen,
) {
    if (rememberReducedMotion()) {
        LilyMark(modifier, size, petal, stamen)
        return
    }
    val transition = rememberInfiniteTransition(label = "bloom")
    val alphas = (0 until 5).map { i ->
        transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 1600
                    0.25f at 0
                    1f at 420
                    0.25f at 1100
                },
                repeatMode = RepeatMode.Restart,
                initialStartOffset = StartOffset(i * 170),
            ),
            label = "petal$i",
        )
    }
    Canvas(modifier.size(size)) { bloom(petal, stamen) { i -> alphas[i].value } }
}

/** A speech bubble for the Chat section. */
val ChatGlyph: ImageVector = materialIcon(name = "LilyChat") {
    materialPath {
        moveTo(20f, 2f)
        horizontalLineTo(4f)
        curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
        verticalLineToRelative(18f)
        lineToRelative(4f, -4f)
        horizontalLineToRelative(14f)
        curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
        verticalLineTo(4f)
        curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
        close()
    }
}
