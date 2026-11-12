package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.data.local.UiTier
import com.example.ui.designsystem.LocalAppLanguage
import com.example.ui.designsystem.LocalIsDarkTheme
import com.example.ui.designsystem.LocalPortalRole
import com.example.ui.designsystem.LocalRolePalette
import com.example.ui.designsystem.LocalUiTier
import com.example.ui.designsystem.PlusJakartaSans
import com.example.ui.designsystem.RolePalette
import com.example.ui.designsystem.WciColors

val WciTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 36.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.8).sp
    ),
    displayMedium = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.6).sp
    ),
    headlineLarge = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.4).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp
    ),
    titleLarge = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.Bold,
        fontSize = 19.sp,
        lineHeight = 25.sp
    ),
    titleMedium = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
        lineHeight = 23.sp
    ),
    titleSmall = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 21.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 23.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 21.sp
    ),
    bodySmall = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.2.sp
    )
)

@Composable
fun WaqarInstituteTheme(
    darkTheme: Boolean = true,
    rolePalette: RolePalette = WciColors.AdminPalette,
    uiTier: UiTier = UiTier.HIGH,
    languageCode: String = "EN",
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = rolePalette.start,
            secondary = rolePalette.end,
            tertiary = WciColors.Info,
            background = WciColors.BgPrimaryDark,
            surface = WciColors.BgElevatedDark,
            surfaceVariant = WciColors.BgContainerDark,
            onPrimary = Color(0xFF05060F),
            onSecondary = Color(0xFF05060F),
            onBackground = WciColors.TextPrimaryDark,
            onSurface = WciColors.TextPrimaryDark,
            onSurfaceVariant = WciColors.TextMutedDark,
            error = WciColors.Danger
        )
    } else {
        lightColorScheme(
            primary = rolePalette.start,
            secondary = rolePalette.end,
            tertiary = WciColors.Info,
            background = WciColors.BgPrimaryLight,
            surface = WciColors.BgElevatedLight,
            surfaceVariant = WciColors.BgContainerLight,
            onPrimary = Color.White,
            onSecondary = Color.White,
            onBackground = WciColors.TextPrimaryLight,
            onSurface = WciColors.TextPrimaryLight,
            onSurfaceVariant = WciColors.TextMutedLight,
            error = WciColors.Danger
        )
    }

    CompositionLocalProvider(
        LocalUiTier provides uiTier,
        LocalRolePalette provides rolePalette,
        LocalPortalRole provides rolePalette.role,
        LocalIsDarkTheme provides darkTheme,
        LocalAppLanguage provides languageCode
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = WciTypography,
            content = content
        )
    }
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    WaqarInstituteTheme(darkTheme = darkTheme, content = content)
}

