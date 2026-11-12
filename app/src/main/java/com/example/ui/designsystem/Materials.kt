package com.example.ui.designsystem

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.UiTier
import com.example.data.model.PortalRole
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ============================================================================
// 1. FOUR-PORTAL MATERIAL ENGINE (PRD ARCHITECTURE SPECIFICATION):
//    - STUDENT -> 3D Claymorphism (Soft puffy 3D cards, dual top-left light & bottom-right bevel, bright luminous clay base — NEVER black)
//    - TEACHER -> Glassmorphism (Frosted emerald/teal backdrop, 12-18% white tint, 1dp white specular rim)
//    - PARENT  -> Liquid Glass (Clear lens-like glass with specular top-left rim, warm rose/amber refraction)
//    - ADMIN   -> Liquid Glass (Deep indigo base, lens-like panels with specular edge, violet & cyan glow)
// ============================================================================

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = WciRadius.lg,
    glowColor: Color? = null,
    strongSurface: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(WciSpacing.md),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val palette = LocalRolePalette.current
    val portalRole = LocalPortalRole.current
    val isStudentClay = portalRole == PortalRole.STUDENT
    val isTeacherGlass = portalRole == PortalRole.TEACHER
    val isParentLiquid = portalRole == PortalRole.PARENT

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed && onClick != null) {
            if (isStudentClay) 0.955f else 0.97f
        } else 1f,
        animationSpec = WciMotion.bouncySpring(),
        label = "cardPressScale"
    )

    val effectiveCorner = if (isStudentClay) 28.dp else cornerRadius
    val shape = RoundedCornerShape(effectiveCorner)
    val accentStart = glowColor ?: palette.start
    val accentEnd = palette.end

    val surfaceBrush = when {
        isStudentClay -> {
            // 3D Claymorphism: Soft puffy luminous indigo-violet clay surface (never dark/black!)
            Brush.linearGradient(
                colors = listOf(
                    Color(0xFF28377E).copy(alpha = 0.97f),
                    accentStart.copy(alpha = if (strongSurface) 0.42f else 0.30f),
                    Color(0xFF1F2B68).copy(alpha = 0.97f)
                ),
                start = Offset(0f, 0f),
                end = Offset(900f, 700f)
            )
        }
        isTeacherGlass -> {
            // Glassmorphism: Frosted emerald/cyan translucent glass with 16% white tint
            Brush.linearGradient(
                colors = listOf(
                    Color(0xFF133346).copy(alpha = 0.84f),
                    Color.White.copy(alpha = 0.16f),
                    accentStart.copy(alpha = 0.24f),
                    Color(0xFF0E2638).copy(alpha = 0.86f)
                ),
                start = Offset(0f, 0f),
                end = Offset(950f, 650f)
            )
        }
        isParentLiquid -> {
            // Parent Liquid Glass: Clear lens-like glass with warm rose & amber refraction
            Brush.linearGradient(
                colors = listOf(
                    Color(0xFF2A183B).copy(alpha = 0.84f),
                    Color(0xFFFF6B8B).copy(alpha = if (strongSurface) 0.30f else 0.20f),
                    Color(0xFFFF9F43).copy(alpha = 0.16f),
                    Color(0xFF1E1330).copy(alpha = 0.86f)
                ),
                start = Offset(0f, 0f),
                end = Offset(1000f, 650f)
            )
        }
        else -> {
            // Admin Liquid Glass: Deep indigo base, lens-like panels with violet & cyan glow
            if (isDark) {
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF182254).copy(alpha = if (strongSurface) 0.88f else 0.78f),
                        accentStart.copy(alpha = if (strongSurface) 0.28f else 0.18f),
                        accentEnd.copy(alpha = if (strongSurface) 0.22f else 0.14f),
                        Color(0xFF10173D).copy(alpha = if (strongSurface) 0.88f else 0.80f)
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(1000f, 650f)
                )
            } else {
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF24347A).copy(alpha = 0.90f),
                        accentStart.copy(alpha = 0.28f),
                        accentEnd.copy(alpha = 0.22f),
                        Color(0xFF1B2760).copy(alpha = 0.90f)
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(1000f, 650f)
                )
            }
        }
    }

    val borderBrush = when {
        isStudentClay -> Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.68f),
                accentStart.copy(alpha = 0.72f),
                Color(0xFF0F1738).copy(alpha = 0.45f)
            ),
            start = Offset(0f, 0f),
            end = Offset(700f, 700f)
        )
        isTeacherGlass -> Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.55f),
                Color(0xFF10F596).copy(alpha = 0.55f),
                Color.White.copy(alpha = 0.25f)
            ),
            start = Offset(0f, 0f),
            end = Offset(700f, 700f)
        )
        else -> Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.60f),
                accentStart.copy(alpha = 0.70f),
                accentEnd.copy(alpha = 0.55f),
                Color.White.copy(alpha = 0.22f)
            ),
            start = Offset(0f, 0f),
            end = Offset(700f, 700f)
        )
    }

    val clickMod = if (onClick != null) {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .scale(pressScale)
            .shadow(
                elevation = if (isStudentClay) 18.dp else 10.dp,
                shape = shape,
                ambientColor = accentStart.copy(alpha = 0.48f),
                spotColor = accentEnd.copy(alpha = 0.48f)
            )
            .clip(shape)
            .background(brush = surfaceBrush, shape = shape)
            .drawBehind {
                val rPx = effectiveCorner.toPx()
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            accentStart.copy(alpha = if (isStudentClay) 0.28f else 0.20f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.18f, size.height * 0.15f),
                        radius = size.maxDimension * 0.75f
                    )
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            accentEnd.copy(alpha = 0.18f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.88f, size.height * 0.85f),
                        radius = size.maxDimension * 0.65f
                    )
                )

                if (isStudentClay) {
                    // 3D Claymorphism 3dp inner top-left puffy highlight & bottom-right bevel shadow
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.32f),
                        topLeft = Offset(3.dp.toPx(), 3.dp.toPx()),
                        size = Size(size.width - 6.dp.toPx(), size.height - 6.dp.toPx()),
                        cornerRadius = CornerRadius(rPx, rPx),
                        style = Stroke(width = 3.dp.toPx())
                    )
                    drawRoundRect(
                        color = Color(0xFF0D1435).copy(alpha = 0.34f),
                        topLeft = Offset(5.dp.toPx(), 5.dp.toPx()),
                        size = Size(size.width - 6.dp.toPx(), size.height - 6.dp.toPx()),
                        cornerRadius = CornerRadius(rPx, rPx),
                        style = Stroke(width = 2.5.dp.toPx())
                    )
                } else {
                    // Liquid Glass & Glassmorphism top specular reflection rim
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = if (isTeacherGlass) 0.16f else 0.20f),
                                Color.Transparent
                            ),
                            startY = 0f,
                            endY = size.height * 0.38f
                        )
                    )
                }
            }
            .border(width = if (isStudentClay) 2.2.dp else 1.2.dp, brush = borderBrush, shape = shape)
            .then(clickMod)
            .padding(contentPadding)
    ) {
        Column(content = content)
    }
}

@Composable
fun LiquidPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    palette: RolePalette = LocalRolePalette.current,
    badgeCount: Int? = null,
    testTag: String? = null
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.92f
            selected -> 1.03f
            else -> 1f
        },
        animationSpec = WciMotion.bouncySpring(),
        label = "pillScale"
    )
    val bgAlpha by animateFloatAsState(
        targetValue = if (selected) 0.42f else 0.18f,
        animationSpec = WciMotion.fastTween(),
        label = "pillBgAlpha"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) palette.start else Color.White.copy(alpha = 0.28f),
        animationSpec = WciMotion.fastTween(),
        label = "pillBorder"
    )
    val shape = RoundedCornerShape(WciRadius.pill)

    val pillBrush = if (selected) {
        Brush.linearGradient(
            colors = listOf(
                palette.start.copy(alpha = bgAlpha),
                palette.end.copy(alpha = bgAlpha * 0.9f)
            )
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                Color(0xFF1B255E).copy(alpha = 0.78f),
                palette.start.copy(alpha = 0.14f)
            )
        )
    }

    Row(
        modifier = modifier
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .scale(scale)
            .heightIn(min = 38.dp)
            .clip(shape)
            .background(brush = pillBrush, shape = shape)
            .border(width = if (selected) 1.5.dp else 1.dp, color = borderColor, shape = shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = text,
                tint = if (selected) Color.White else palette.start,
                modifier = Modifier.size(16.dp)
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(
                color = if (selected) Color.White else WciColors.TextPrimaryDark,
                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (badgeCount != null && badgeCount > 0) {
            Box(
                modifier = Modifier
                    .background(palette.brush, CircleShape)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = badgeCount.toString(),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = JetBrainsMono,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF05060F)
                    )
                )
            }
        }
    }
}

// ============================================================================
// 2. CLAYMORPHISM 3D ACTION TILE (Bouncy Tactile Card with Gradient Icon Orb)
// ============================================================================

@Composable
fun ClayActionTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = WciMotion.bouncySpring(),
        label = "clayScale"
    )
    val shape = RoundedCornerShape(WciRadius.xl)

    val clayBrush = Brush.linearGradient(
        colors = listOf(
            accentColor.copy(alpha = 0.36f),
            Color(0xFF1A255C).copy(alpha = 0.92f),
            accentColor.copy(alpha = 0.22f)
        ),
        start = Offset(0f, 0f),
        end = Offset(500f, 400f)
    )

    Box(
        modifier = modifier
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .scale(scale)
            .shadow(
                elevation = 14.dp,
                shape = shape,
                ambientColor = accentColor.copy(alpha = 0.5f),
                spotColor = accentColor.copy(alpha = 0.5f)
            )
            .clip(shape)
            .background(clayBrush, shape)
            .drawBehind {
                // Top-left 3D clay specular rim
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.25f),
                    topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
                    size = Size(size.width - 4.dp.toPx(), size.height - 4.dp.toPx()),
                    cornerRadius = CornerRadius(WciRadius.xl.toPx(), WciRadius.xl.toPx()),
                    style = Stroke(width = 2.5.dp.toPx())
                )
            }
            .border(
                width = 1.5.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.6f),
                        accentColor.copy(alpha = 0.7f)
                    )
                ),
                shape = shape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(WciSpacing.md)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                accentColor,
                                accentColor.copy(alpha = 0.65f)
                            )
                        ),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = Color(0xFF060919),
                    modifier = Modifier.size(24.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = WciColors.TextSecondaryDark
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ============================================================================
// 3. NEON GRADIENT BORDER & SHIMMER CTA BUTTON
// ============================================================================

fun Modifier.neonBorder(
    palette: RolePalette,
    cornerRadius: Dp = WciRadius.lg,
    borderWidth: Dp = 1.6.dp
): Modifier = this.drawWithContent {
    drawContent()
    val rPx = cornerRadius.toPx()
    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(palette.start, palette.end, Color.White.copy(alpha = 0.6f), palette.start),
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height)
        ),
        cornerRadius = CornerRadius(rPx, rPx),
        style = Stroke(width = borderWidth.toPx())
    )
}

@Composable
fun ShimmerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    palette: RolePalette = LocalRolePalette.current,
    enabled: Boolean = true,
    testTag: String? = null
) {
    val haptic = LocalHapticFeedback.current
    val uiTier = LocalUiTier.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.94f else 1f,
        animationSpec = WciMotion.bouncySpring(),
        label = "shimmerBtnScale"
    )
    val transition = rememberInfiniteTransition(label = "shimmerTransition")
    val shimmerProgress by transition.animateFloat(
        initialValue = -0.4f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerSweep"
    )

    val shape = RoundedCornerShape(WciRadius.pill)
    Box(
        modifier = modifier
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .scale(pressScale)
            .heightIn(min = 48.dp)
            .shadow(
                elevation = if (enabled) 10.dp else 0.dp,
                shape = shape,
                ambientColor = palette.start.copy(alpha = 0.6f),
                spotColor = palette.end.copy(alpha = 0.6f)
            )
            .clip(shape)
            .background(
                brush = if (enabled) palette.brush else Brush.linearGradient(
                    listOf(WciColors.TextDisabledDark, WciColors.TextDisabledDark)
                ),
                shape = shape
            )
            .border(1.dp, Color.White.copy(alpha = 0.45f), shape)
            .drawWithContent {
                drawContent()
                if (enabled && uiTier != UiTier.REDUCED) {
                    val xCenter = size.width * shimmerProgress
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.38f),
                                Color.Transparent
                            ),
                            start = Offset(xCenter - 90f, 0f),
                            end = Offset(xCenter + 90f, size.height)
                        )
                    )
                }
            }
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = text,
                    tint = Color(0xFF05060F),
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.copy(
                    color = Color(0xFF05060F),
                    fontWeight = FontWeight.ExtraBold
                )
            )
        }
    }
}

// ============================================================================
// 4. MULTI-COLOR LIQUID AURORA & SINE WAVE BACKGROUND ENGINE
//    (Used on Login AND inside the Portal Shell with Per-Tab Color Shifting)
// ============================================================================

private const val NEON_AURORA_AGSL = """
    uniform float2 uResolution;
    uniform float uTime;
    uniform float4 uColorStart;
    uniform float4 uColorEnd;

    half4 main(float2 fragCoord) {
        float2 uv = fragCoord / uResolution.xy;
        // Rich sapphire-violet base (never pitch black!)
        float3 baseBg = float3(0.055, 0.082, 0.225);

        float w1 = sin(uv.x * 4.5 + uTime * 1.3) * 0.14 + 0.30;
        float w2 = cos(uv.x * 3.6 - uTime * 0.95 + 1.4) * 0.14 + 0.56;
        float w3 = sin(uv.x * 5.8 + uTime * 1.6 + 2.8) * 0.11 + 0.78;

        float g1 = smoothstep(0.28, 0.0, abs(uv.y - w1));
        float g2 = smoothstep(0.30, 0.0, abs(uv.y - w2));
        float g3 = smoothstep(0.25, 0.0, abs(uv.y - w3));

        float3 col = baseBg;
        col += uColorStart.rgb * (g1 * 0.58 + g3 * 0.28);
        col += uColorEnd.rgb * (g2 * 0.54 + g3 * 0.26);
        col += float3(0.0, 0.85, 1.0) * (g3 * 0.22);
        return half4(col, 1.0);
    }
"""

@Composable
fun NeonWaveBackground(
    palette: RolePalette,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val uiTier = LocalUiTier.current
    val isDark = LocalIsDarkTheme.current
    val infiniteTransition = rememberInfiniteTransition(label = "waveBgTransition")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (uiTier == UiTier.REDUCED) 20000 else 8000,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )

    val animStartColor by animateColorAsState(
        targetValue = palette.start,
        animationSpec = tween(durationMillis = 550, easing = FastOutSlowInEasing),
        label = "bgWaveStartColor"
    )
    val animEndColor by animateColorAsState(
        targetValue = palette.end,
        animationSpec = tween(durationMillis = 550, easing = FastOutSlowInEasing),
        label = "bgWaveEndColor"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = if (isDark) {
                        listOf(
                            Color(0xFF0B1130),
                            Color(0xFF131B47),
                            Color(0xFF090E28)
                        )
                    } else {
                        listOf(
                            Color(0xFF1A2663),
                            Color(0xFF23327A),
                            Color(0xFF151F52)
                        )
                    }
                )
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && uiTier == UiTier.ULTRA && isDark) {
                val shader = RuntimeShader(NEON_AURORA_AGSL).apply {
                    setFloatUniform("uResolution", w, h)
                    setFloatUniform("uTime", phase)
                    setFloatUniform(
                        "uColorStart",
                        animStartColor.red,
                        animStartColor.green,
                        animStartColor.blue,
                        1f
                    )
                    setFloatUniform(
                        "uColorEnd",
                        animEndColor.red,
                        animEndColor.green,
                        animEndColor.blue,
                        1f
                    )
                }
                drawRect(brush = ShaderBrush(shader))
            } else {
                // Multi-color radial aurora blooms + 4 flowing liquid sine waves
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(animStartColor.copy(alpha = 0.34f), Color.Transparent),
                        center = Offset(w * (0.22f + 0.08f * sin(phase)), h * 0.20f),
                        radius = w * 0.78f
                    )
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(animEndColor.copy(alpha = 0.30f), Color.Transparent),
                        center = Offset(w * (0.80f - 0.08f * cos(phase)), h * 0.55f),
                        radius = w * 0.75f
                    )
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(WciColors.Info.copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(w * 0.45f, h * 0.85f),
                        radius = w * 0.70f
                    )
                )

                val waveColors = listOf(
                    animStartColor,
                    animEndColor,
                    WciColors.Info,
                    Color(0xFFA855F7)
                )

                for (waveIndex in 0..3) {
                    val path = Path()
                    val yBase = h * (0.22f + waveIndex * 0.20f)
                    val amp = h * (0.045f - waveIndex * 0.006f)
                    path.moveTo(0f, yBase)
                    val step = (w / 36f).coerceAtLeast(8f)
                    var x = 0f
                    while (x <= w + step) {
                        val angle = (x / w) * 2.2f * PI.toFloat() + phase * (1f + waveIndex * 0.25f) + waveIndex * 1.1f
                        val y = yBase + sin(angle) * amp
                        path.lineTo(x, y)
                        x += step
                    }
                    val c1 = waveColors[waveIndex % waveColors.size]
                    val c2 = waveColors[(waveIndex + 1) % waveColors.size]
                    drawPath(
                        path = path,
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                c1.copy(alpha = 0.42f),
                                c2.copy(alpha = 0.48f),
                                c1.copy(alpha = 0.35f)
                            )
                        ),
                        style = Stroke(width = (3.5f - waveIndex * 0.4f).dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }
        }
        content()
    }
}

// ============================================================================
// 5. ANIMATED COUNTERS, MONEY TICKER, SPARKLINE & DONUT PROGRESS RING
// ============================================================================

@Composable
fun CountUpInt(
    targetValue: Int,
    modifier: Modifier = Modifier,
    prefix: String = "",
    suffix: String = "",
    color: Color = WciColors.TextPrimaryDark,
    fontSizeSp: Int = 24
) {
    val animated by animateIntAsState(
        targetValue = targetValue,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "countUpInt"
    )
    Text(
        text = "$prefix$animated$suffix",
        modifier = modifier,
        style = MaterialTheme.typography.headlineLarge.copy(
            fontFamily = JetBrainsMono,
            fontSize = fontSizeSp.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
    )
}

fun formatInrPaise(amountPaise: Long): String {
    val rupees = amountPaise / 100.0
    val format = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN"))
    format.maximumFractionDigits = 0
    return format.format(rupees)
}

@Composable
fun MoneyTicker(
    amountPaise: Long,
    modifier: Modifier = Modifier,
    color: Color = WciColors.Success,
    fontSizeSp: Int = 22
) {
    val targetRupees = (amountPaise / 100L).toInt()
    val animatedRupees by animateIntAsState(
        targetValue = targetRupees,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "moneyTicker"
    )
    val formatted = remember(animatedRupees) {
        val format = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN"))
        format.maximumFractionDigits = 0
        format.format(animatedRupees)
    }
    Text(
        text = formatted,
        modifier = modifier,
        style = MaterialTheme.typography.headlineMedium.copy(
            fontFamily = JetBrainsMono,
            fontSize = fontSizeSp.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
    )
}

@Composable
fun SparklineCanvas(
    points: List<Float>,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (points.size < 2) return@Canvas
        val minVal = points.minOrNull() ?: 0f
        val maxVal = (points.maxOrNull() ?: 1f).coerceAtLeast(minVal + 1f)
        val range = (maxVal - minVal).coerceAtLeast(1f)

        val linePath = Path()
        val fillPath = Path()
        val stepX = size.width / (points.size - 1).coerceAtLeast(1)

        points.forEachIndexed { idx, value ->
            val x = idx * stepX
            val normalizedY = 1f - ((value - minVal) / range)
            val y = normalizedY * (size.height * 0.8f) + size.height * 0.1f
            if (idx == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, size.height)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(size.width, size.height)
        fillPath.close()

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0.38f), Color.Transparent)
            )
        )
        drawPath(
            path = linePath,
            color = color,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

@Composable
fun DonutProgressRing(
    progress: Float,
    color: Color,
    label: String,
    subLabel: String,
    modifier: Modifier = Modifier,
    sizeDp: Dp = 84.dp
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 750, easing = FastOutSlowInEasing),
        label = "donutProgress"
    )
    Box(
        modifier = modifier.size(sizeDp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 8.dp.toPx()
            drawArc(
                color = Color.White.copy(alpha = 0.16f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(color, WciColors.Info, color)
                ),
                startAngle = -90f,
                sweepAngle = 360f * animatedProgress,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontFamily = JetBrainsMono,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
            )
            Text(
                text = subLabel,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 10.sp,
                    color = WciColors.TextSecondaryDark
                )
            )
        }
    }
}

// ============================================================================
// 6. HOLD-TO-CONFIRM DESTRUCTIVE ACTION & EMPTY STATE
// ============================================================================

@Composable
fun HoldToConfirmButton(
    label: String,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var isHolding by remember { mutableStateOf(false) }
    var holdProgress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isHolding) {
        if (isHolding) {
            val start = System.currentTimeMillis()
            while (isHolding) {
                val elapsed = System.currentTimeMillis() - start
                holdProgress = (elapsed / 900f).coerceIn(0f, 1f)
                if (holdProgress >= 1f) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onConfirmed()
                    isHolding = false
                    holdProgress = 0f
                    break
                }
                delay(16L)
            }
        } else {
            holdProgress = 0f
        }
    }

    val shape = RoundedCornerShape(WciRadius.pill)
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(WciColors.Danger.copy(alpha = 0.22f), shape)
            .border(1.dp, WciColors.Danger.copy(alpha = 0.65f), shape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isHolding = true
                        tryAwaitRelease()
                        isHolding = false
                    }
                )
            }
            .drawBehind {
                drawRect(
                    color = WciColors.Danger.copy(alpha = 0.45f),
                    size = Size(size.width * holdProgress, size.height)
                )
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (isHolding) "Hold ${(holdProgress * 100).toInt()}%..." else "$label (Hold)",
            style = MaterialTheme.typography.labelLarge.copy(
                color = WciColors.Danger,
                fontWeight = FontWeight.Bold
            )
        )
    }
}

@Composable
fun EmptyStateCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    ctaLabel: String? = null,
    onCtaClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val palette = LocalRolePalette.current
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(WciSpacing.xl)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(palette.brush, CircleShape)
                    .border(1.5.dp, Color.White.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = Color(0xFF05060F),
                    modifier = Modifier.size(28.dp)
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold
                )
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = WciColors.TextSecondaryDark
                )
            )
            if (ctaLabel != null && onCtaClick != null) {
                Spacer(modifier = Modifier.height(4.dp))
                ShimmerButton(
                    text = ctaLabel,
                    onClick = onCtaClick,
                    palette = palette
                )
            }
        }
    }
}

// ============================================================================
// 7. DETERMINISTIC QR VERIFICATION BADGE CANVAS
// ============================================================================

@Composable
fun QrMatrixBadge(
    payload: String,
    modifier: Modifier = Modifier,
    sizeDp: Dp = 96.dp
) {
    val qrBitmap = remember(payload) {
        com.waqar.institute.certificate.CertificateQrGenerator.generateScannableQrBitmap(
            content = payload.ifBlank { "https://waqarinstitute.edu.in/verify" },
            sizePx = 360
        )
    }
    Box(
        modifier = modifier
            .size(sizeDp)
            .clip(RoundedCornerShape(WciRadius.sm))
            .background(Color.White)
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Image(
            bitmap = qrBitmap.asImageBitmap(),
            contentDescription = "Scannable QR Code for $payload",
            modifier = Modifier.fillMaxSize()
        )
    }
}
