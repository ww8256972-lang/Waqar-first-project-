package com.example.ui.designsystem

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.UiTier
import com.example.data.model.PortalRole

val PlusJakartaSans = FontFamily(
    Font(R.font.plus_jakarta_sans, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans, FontWeight.Bold),
    Font(R.font.plus_jakarta_sans, FontWeight.ExtraBold)
)

val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal),
    Font(R.font.jetbrains_mono, FontWeight.Medium),
    Font(R.font.jetbrains_mono, FontWeight.SemiBold),
    Font(R.font.jetbrains_mono, FontWeight.Bold)
)

@Immutable
data class RolePalette(
    val role: PortalRole,
    val start: Color,
    val end: Color,
    val glow: Color,
    val tagBackground: Color
) {
    val brush: Brush
        get() = Brush.linearGradient(listOf(start, end))
}

object WciColors {
    // Rich Deep Sapphire & Indigo Aurora Palette (Replaces flat black)
    val BgPrimaryDark = Color(0xFF090D24)
    val BgElevatedDark = Color(0xFF12183B)
    val BgContainerDark = Color(0xFF1A2252)
    val GlassSurfaceDark = Color(0x94151D48)
    val GlassSurfaceStrongDark = Color(0xCC1B255A)
    val GlassBorderLight = Color(0x52FFFFFF)
    val GlassBorderSubtle = Color(0x24FFFFFF)

    val TextPrimaryDark = Color(0xFFFFFFFF)
    val TextSecondaryDark = Color(0xFFEAF0FF)
    val TextMutedDark = Color(0xFFC8D6FF)
    val TextDisabledDark = Color(0xFF8E9ECF)

    // Daylight Iridescent Sapphire Palette (Ensures crisp contrast without washed-out yellow)
    val BgPrimaryLight = Color(0xFF182254)
    val BgElevatedLight = Color(0xFF212E6E)
    val BgContainerLight = Color(0xFF2A3B88)
    val GlassSurfaceLight = Color(0xD91E2B68)
    val GlassBorderLightMode = Color(0x66FFFFFF)
    val TextPrimaryLight = Color(0xFFFFFFFF)
    val TextSecondaryLight = Color(0xFFE0E7FF)
    val TextMutedLight = Color(0xFFBBD0FF)

    // Semantic Status Tokens (Crisp Neon)
    val Success = Color(0xFF14F195)
    val Warning = Color(0xFF38BDF8) // Crisp Sky Cyan instead of muddy yellow
    val AmberHighlight = Color(0xFFFF9F43)
    val Danger = Color(0xFFFF4D79)
    val Info = Color(0xFF00E5FF)
    val AiAccentStart = Color(0xFFA970FF)
    val AiAccentEnd = Color(0xFF00E5FF)

    // Role Palettes
    val AdminPalette = RolePalette(
        role = PortalRole.ADMIN,
        start = Color(0xFFA855F7),
        end = Color(0xFFEC4899),
        glow = Color(0x66A855F7),
        tagBackground = Color(0x33A855F7)
    )
    val StudentPalette = RolePalette(
        role = PortalRole.STUDENT,
        start = Color(0xFF00E5FF),
        end = Color(0xFF6366F1),
        glow = Color(0x6600E5FF),
        tagBackground = Color(0x3300E5FF)
    )
    val TeacherPalette = RolePalette(
        role = PortalRole.TEACHER,
        start = Color(0xFF14F195),
        end = Color(0xFF00C6FF),
        glow = Color(0x6614F195),
        tagBackground = Color(0x3314F195)
    )
    val ParentPalette = RolePalette(
        role = PortalRole.PARENT,
        start = Color(0xFFFF6B8B),
        end = Color(0xFFA855F7),
        glow = Color(0x66FF6B8B),
        tagBackground = Color(0x33FF6B8B)
    )

    // Distinct Multi-Color Tab Palettes so every tab has its own vibrant liquid wave color scheme
    val StudentsTabPalette = RolePalette(
        role = PortalRole.STUDENT,
        start = Color(0xFF00E5FF),
        end = Color(0xFF3B82F6),
        glow = Color(0x6600E5FF),
        tagBackground = Color(0x3300E5FF)
    )
    val AttendanceTabPalette = RolePalette(
        role = PortalRole.TEACHER,
        start = Color(0xFF10F596),
        end = Color(0xFF06B6D4),
        glow = Color(0x6610F596),
        tagBackground = Color(0x3310F596)
    )
    val FeesTabPalette = RolePalette(
        role = PortalRole.ADMIN,
        start = Color(0xFFFF5894),
        end = Color(0xFF8B5CF6),
        glow = Color(0x66FF5894),
        tagBackground = Color(0x33FF5894)
    )
    val ExamsTabPalette = RolePalette(
        role = PortalRole.STUDENT,
        start = Color(0xFF818CF8),
        end = Color(0xFFE879F9),
        glow = Color(0x66818CF8),
        tagBackground = Color(0x33818CF8)
    )
    val InboxTabPalette = RolePalette(
        role = PortalRole.ADMIN,
        start = Color(0xFF38BDF8),
        end = Color(0xFFA855F7),
        glow = Color(0x6638BDF8),
        tagBackground = Color(0x3338BDF8)
    )

    fun paletteForRole(role: PortalRole): RolePalette = when (role) {
        PortalRole.ADMIN -> AdminPalette
        PortalRole.STUDENT -> StudentPalette
        PortalRole.TEACHER -> TeacherPalette
        PortalRole.PARENT -> ParentPalette
    }

    fun paletteForKey(roleKey: String): RolePalette =
        paletteForRole(PortalRole.fromKey(roleKey))

    fun paletteForRoute(routeId: String, fallbackRole: PortalRole): RolePalette = when (routeId) {
        "M01_DASHBOARD" -> paletteForRole(fallbackRole)
        "M03_STUDENTS", "M04_ADMISSIONS", "M15_PARENTS" -> StudentsTabPalette
        "M05_COURSES", "M06_BATCHES", "M07_ATTENDANCE" -> AttendanceTabPalette
        "M08_FEES", "M09_LEDGER", "M14_PAYROLL" -> FeesTabPalette
        "M10_EXAMS", "M11_RESULTS", "M12_MATERIALS", "M13_CERTIFICATES" -> ExamsTabPalette
        "M16_ANNOUNCEMENTS", "M17_LIVE_CLASSES", "M18_AI_HUB" -> InboxTabPalette
        else -> paletteForRole(fallbackRole)
    }
}

object WciRadius {
    val xs: Dp = 8.dp
    val sm: Dp = 12.dp
    val md: Dp = 16.dp
    val lg: Dp = 22.dp
    val xl: Dp = 28.dp
    val pill: Dp = 999.dp
}

object WciSpacing {
    val xxs: Dp = 4.dp
    val xs: Dp = 8.dp
    val sm: Dp = 12.dp
    val md: Dp = 16.dp
    val lg: Dp = 20.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
}

object WciMotion {
    val EasingOutExpo = CubicBezierEasing(0.22f, 1.0f, 0.36f, 1.0f)
    val EasingStandard = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

    fun <T> bouncySpring() = spring<T>(
        dampingRatio = 0.52f,
        stiffness = 320f
    )

    fun <T> snappySpring() = spring<T>(
        dampingRatio = 0.72f,
        stiffness = 480f
    )

    fun <T> gentleSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    fun <T> fastTween(durationMs: Int = 220) = tween<T>(
        durationMillis = durationMs,
        easing = EasingOutExpo
    )
}

val LocalUiTier = staticCompositionLocalOf { UiTier.HIGH }
val LocalRolePalette = staticCompositionLocalOf { WciColors.AdminPalette }
val LocalPortalRole = staticCompositionLocalOf { PortalRole.ADMIN }
val LocalIsDarkTheme = staticCompositionLocalOf { true }
val LocalAppLanguage = staticCompositionLocalOf { "EN" }
