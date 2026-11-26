package dev.breno.jarvis.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.breno.jarvis.model.JarvisStatus
import dev.breno.jarvis.ui.theme.JarvisColors
import kotlin.math.PI
import kotlin.math.sin

/**
 * Orb central: gradiente radial cuja cor depende do estado, que pulsa com o
 * volume do microfone ao ouvir e "respira" suavemente ao pensar/falar.
 */
@Composable
fun Orb(
    status: JarvisStatus,
    micLevel: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
) {
    val targetColor = when (status) {
        JarvisStatus.IDLE -> JarvisColors.CyanSoft
        JarvisStatus.LISTENING -> JarvisColors.Cyan
        JarvisStatus.THINKING -> JarvisColors.Amber
        JarvisStatus.SPEAKING -> JarvisColors.Purple
    }
    val color by animateColorAsState(targetColor, tween(400), label = "orbColor")

    // Volume do microfone suavizado (o RMS chega aos trancos).
    val level by animateFloatAsState(
        targetValue = if (status == JarvisStatus.LISTENING) micLevel else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "micLevel",
    )

    val infinite = rememberInfiniteTransition(label = "orb")
    val breathDuration = when (status) {
        JarvisStatus.THINKING -> 700
        JarvisStatus.SPEAKING -> 1100
        else -> 2600
    }
    val breath by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(breathDuration, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing)),
        label = "phase",
    )

    val breathAmount = when (status) {
        JarvisStatus.IDLE -> 0.03f
        JarvisStatus.LISTENING -> 0.02f
        JarvisStatus.THINKING -> 0.06f
        JarvisStatus.SPEAKING -> 0.08f
    }
    val scale = 0.78f + breath * breathAmount + level * 0.18f
    val glow = when (status) {
        JarvisStatus.IDLE -> 0.35f
        JarvisStatus.LISTENING -> 0.55f + level * 0.4f
        JarvisStatus.THINKING, JarvisStatus.SPEAKING -> 0.5f + breath * 0.3f
    }

    Canvas(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = "Falar com o Jarvis",
                onClick = onClick,
            ),
    ) {
        val maxRadius = this.size.minDimension / 2f
        val radius = maxRadius * scale

        // Halo externo
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = glow * 0.6f), Color.Transparent),
                center = center,
                radius = maxRadius,
            ),
            radius = maxRadius,
        )

        // Corpo do orb
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.9f),
                    color,
                    color.copy(alpha = 0.55f),
                    JarvisColors.Background.copy(alpha = 0.9f),
                ),
                center = center + Offset(-radius * 0.15f, -radius * 0.2f),
                radius = radius * 1.1f,
            ),
            radius = radius,
        )

        // Anel girando/ondulando ao pensar e falar
        if (status == JarvisStatus.THINKING || status == JarvisStatus.SPEAKING) {
            val ring = radius * (1.06f + 0.03f * sin(phase * 2))
            drawCircle(
                color = color.copy(alpha = 0.5f),
                radius = ring,
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}
