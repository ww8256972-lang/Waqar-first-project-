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
    val TextSecondaryDark = Color(0xFFF5F8FF)
    val TextMutedDark = Color(0xFFDCE6FF)
    val TextDisabledDark = Color(0xFFAAB9E8)

    // Daylight Frosted Slate-Sapphire Palette (Genuine high-contrast light mode)
    val BgPrimaryLight = Color(0xFFF1F5F9)
    val BgElevatedLight = Color(0xFFFFFFFF)
    val BgContainerLight = Color(0xFFE2E8F0)
    val GlassSurfaceLight = Color(0xEEFFFFFF)
    val GlassBorderLightMode = Color(0x470F172A)
    val TextPrimaryLight = Color(0xFF0F172A)
    val TextSecondaryLight = Color(0xFF334155)
    val TextMutedLight = Color(0xFF475569)

    // Semantic Status Tokens (Crisp Neon Dark + Deep High-Contrast Light)
    val Success = Color(0xFF14F195)
    val SuccessLight = Color(0xFF047857)
    val Warning = Color(0xFF38BDF8) // Crisp Sky Cyan instead of muddy yellow
    val WarningLight = Color(0xFF0369A1)
    val AmberHighlight = Color(0xFFFF9F43)
    val Danger = Color(0xFFFF4D79)
    val DangerLight = Color(0xFFBE123C)
    val Info = Color(0xFF00E5FF)
    val InfoLight = Color(0xFF0284C7)
    val AiAccentStart = Color(0xFFA970FF)
    val AiAccentEnd = Color(0xFF00E5FF)

    // Biometric & Scanner Semantic Tokens
    val BiometricSubtext = Color(0xFFF0F5FF)
    val ScannerGreen = Color(0xFF22C55E)
    val BiometricMintBadgeText = Color(0xFFBBF7D0)
    val ScannerRed = Color(0xFFEF4444)
    val BiometricCoralAlert = Color(0xFFFCA5A5)
    val EmeraldVerified = Color(0xFF16A34A)
    val EmeraldAction = Color(0xFF10B981)
    val ScannerPurple = Color(0xFF7C3AED)
    val ScannerBlue = Color(0xFF2563EB)
    val ScannerTeal = Color(0xFF06B6D4)
    val ScannerPink = Color(0xFFEC4899)
    val ScannerOrange = Color(0xFFF97316)
    val ScannerViolet = Color(0xFFA78BFA)
    val ScannerIndigo = Color(0xFF4F46E5)
    val ScannerYellow = Color(0xFFFDE68A)

    // Shared Semantic UI & Certificate Tokens
    val OnAccentDark = Color(0xFF05060F)
    val SuccessNeon = Color(0xFF10F596)
    val SurfaceDeepNavy = Color(0xFF0F172A)
    val SurfaceSlateDark = Color(0xFF1E293B)
    val SlateBorderMuted = Color(0xFF475569)
    val SlateIconMuted = Color(0xFF94A3B8)
    val CertGold = Color(0xFFD4AF37)
    val CertNavy = Color(0xFF0A1D4E)
    val CertNavySecondary = Color(0xFF1A365D)
    val CertEmerald = Color(0xFF0A5C36)
    val CertAmber = Color(0xFFFFB547)
    val CertBannerOverlay = Color(0xCC070B1E)
    val SandboxDangerBg = Color(0xFF450A0A)
    val SandboxDangerText = Color(0xFFFEE2E2)
    val CrashCardBg = Color(0xFF1E1B4B)
    val UpdateBannerTextDark = Color(0xFF052E16)
    val NeonMagenta = Color(0xFFFF5894)
    val NeonPinkCoral = Color(0xFFFF6B8B)
    val NeonOrchid = Color(0xFFE879F9)
    val IndigoAccent = Color(0xFF818CF8)
    val BlueAccent = Color(0xFF3B82F6)
    val CyanBright = Color(0xFF00C6FF)
    val VioletAccent = Color(0xFF8B5CF6)
    val PurpleVibrant = Color(0xFFA855F7)
    val IndigoDeep = Color(0xFF6366F1)
    val AmberWarm = Color(0xFFF59E0B)
    val MintTextSoft = Color(0xFFA7F3D0)
    val VeilDarkOverlay = Color(0xE60B0F29)
    val SandboxEditorBg = Color(0xFF090D16)
    val SandboxEditorDisabledBg = Color(0xFF1A0B0E)
    val SandboxTerminalBg = Color(0xFF050811)
    val VideoStageDark = Color(0xFF060B19)
    val SurfaceOffWhite = Color(0xFFF8FAFC)
    val LightBorderSubtle = Color(0x260F172A)
    val DrawerScrimDark = Color(0x9904071A)
    val DrawerContainerDark = Color(0xF5121B46)
    val DrawerContainerLight = Color(0xF7FFFFFF)
    val DrawerHeaderDeepDark = Color(0xFF141D4B)
    val DrawerItemDarkStart = Color(0xFF1A255C)
    val NavRailContainerDark = Color(0xE610173D)
    val NavRailContainerLight = Color(0xF5FFFFFF)
    val TopBarStudentStart = Color(0xFF28377E)
    val TopBarStudentEnd = Color(0xFF1F2B68)
    val TopBarTeacherStart = Color(0xFF123244)
    val TopBarTeacherEnd = Color(0xFF0E2536)
    val TopBarParentStart = Color(0xFF2B183D)
    val TopBarParentEnd = Color(0xFF1E122E)
    val TopBarAdminStart = Color(0xFF141D4E)
    val TopBarAdminEnd = Color(0xFF101842)
    val BottomBarClayStart = Color(0xFF25347A)
    val BottomBarDarkStart = Color(0xFF131C4E)
    val BottomBarDarkMid = Color(0xFF1D2869)
    val BottomBarDarkEnd = Color(0xFF111844)
    val SlateTrack = Color(0xFFE2E8F0)

    val textPrimary: Color
        @androidx.compose.runtime.Composable
        get() = if (LocalIsDarkTheme.current) TextPrimaryDark else TextPrimaryLight

    val textSecondary: Color
        @androidx.compose.runtime.Composable
        get() = if (LocalIsDarkTheme.current) TextSecondaryDark else TextSecondaryLight

    val textMuted: Color
        @androidx.compose.runtime.Composable
        get() = if (LocalIsDarkTheme.current) TextMutedDark else TextMutedLight

    val primarySurface: Color
        @androidx.compose.runtime.Composable
        get() = if (LocalIsDarkTheme.current) BgPrimaryDark else BgPrimaryLight

    val elevatedSurface: Color
        @androidx.compose.runtime.Composable
        get() = if (LocalIsDarkTheme.current) BgElevatedDark else BgElevatedLight

    val containerSurface: Color
        @androidx.compose.runtime.Composable
        get() = if (LocalIsDarkTheme.current) BgContainerDark else BgContainerLight

    val cardInnerSurface: Color
        @androidx.compose.runtime.Composable
        get() = if (LocalIsDarkTheme.current) Color(0xFF121A45).copy(alpha = 0.85f) else Color(0xFFF8FAFC)

    val borderMedium: Color
        @androidx.compose.runtime.Composable
        get() = if (LocalIsDarkTheme.current) GlassBorderSubtle else LightBorderSubtle

    val glassBorderSubtleColor: Color
        @androidx.compose.runtime.Composable
        get() = if (LocalIsDarkTheme.current) GlassBorderSubtle else Color(0x260F172A)

    val infoColor: Color
        @androidx.compose.runtime.Composable
        get() = if (LocalIsDarkTheme.current) Info else InfoLight

    val successColor: Color
        @androidx.compose.runtime.Composable
        get() = if (LocalIsDarkTheme.current) Success else SuccessLight

    val warningColor: Color
        @androidx.compose.runtime.Composable
        get() = if (LocalIsDarkTheme.current) Warning else WarningLight

    val dangerColor: Color
        @androidx.compose.runtime.Composable
        get() = if (LocalIsDarkTheme.current) Danger else DangerLight

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
