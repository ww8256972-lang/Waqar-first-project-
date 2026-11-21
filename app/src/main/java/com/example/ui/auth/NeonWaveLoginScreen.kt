package com.example.ui.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.PortalRole
import com.example.ui.designsystem.*
import kotlin.math.roundToInt

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun NeonWaveLoginScreen(
    selectedRole: PortalRole,
    languageCode: String,
    instituteName: String,
    instituteTagline: String,
    isAuthenticating: Boolean = false,
    externalStatusMessage: String? = null,
    pendingAdminSetupUsername: String? = null,
    onRoleSelected: (PortalRole) -> Unit,
    onLanguageToggle: () -> Unit,
    onCredentialLogin: (Context, PortalRole, String, String) -> Unit,
    onCompleteFirstRunAdminSetup: (Context, String, String) -> Unit = { _, _, _ -> },
    onDismissFirstRunAdminSetup: () -> Unit = {}
) {
    val context = LocalContext.current
    val palette = WciColors.paletteForRole(selectedRole)

    var usernameInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var keepSignedIn by remember { mutableStateOf(true) }
    var localErrorMessage by remember { mutableStateOf<String?>(null) }
    var showRecoverySheet by remember { mutableStateOf(false) }
    var failedAttempts by remember { mutableIntStateOf(0) }
    var lockoutUntilEpochMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(externalStatusMessage) {
        if (!externalStatusMessage.isNullOrBlank()) {
            val isInformationalNotice = externalStatusMessage.startsWith("Signed out", ignoreCase = true) ||
                externalStatusMessage.startsWith("Session expired", ignoreCase = true) ||
                externalStatusMessage.startsWith("Signed in", ignoreCase = true) ||
                externalStatusMessage.startsWith("Language set", ignoreCase = true)
            if (!isInformationalNotice) {
                failedAttempts += 1
                if (failedAttempts >= 5) {
                    val backoffSec = (15L shl (failedAttempts - 5).coerceAtMost(4)).coerceAtMost(300L)
                    lockoutUntilEpochMs = System.currentTimeMillis() + backoffSec * 1000L
                }
            }
        }
    }

    val activeMessage = localErrorMessage ?: externalStatusMessage

    // Error horizontal shake animation
    val shakeOffset = remember { Animatable(0f) }
    LaunchedEffect(activeMessage) {
        if (activeMessage != null) {
            for (delta in listOf(-12f, 12f, -8f, 8f, -4f, 4f, 0f)) {
                shakeOffset.animateTo(delta, tween(45))
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Live Neon Wave + Particle Field Background
        NeonWaveBackground(palette = palette)

        // 2. Scrollable Foreground Content inside Safe Drawing Insets
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Top Utility Row: Crest + Language Switcher
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 440.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(palette.brush)
                            .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "W",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = PlusJakartaSans,
                                fontWeight = FontWeight.ExtraBold,
                                color = WciColors.OnAccentDark
                            )
                        )
                    }
                    Column {
                        Text(
                            text = instituteName,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = WciColors.textPrimary
                            )
                        )
                        Text(
                            text = instituteTagline,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = WciColors.textMuted,
                                fontSize = 12.sp
                            )
                        )
                    }
                }

                LiquidPill(
                    text = if (languageCode.equals("EN", ignoreCase = true)) "English • EN" else "Language • $languageCode",
                    selected = true,
                    onClick = onLanguageToggle,
                    icon = Icons.Default.Translate,
                    palette = palette,
                    modifier = Modifier.testTag("language_toggle_pill")
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Center Login Container (constrained to 440.dp max width for tablets)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 440.dp)
                    .offset { IntOffset(shakeOffset.value.roundToInt(), 0) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 4-Role Segmented Selector Bar
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = WciRadius.pill,
                    contentPadding = PaddingValues(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PortalRole.entries.forEach { role ->
                            val rolePal = WciColors.paletteForRole(role)
                            val isSelected = selectedRole == role
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .clip(RoundedCornerShape(WciRadius.pill))
                                    .background(
                                        brush = if (isSelected) rolePal.brush else Brush.linearGradient(
                                            listOf(Color.Transparent, Color.Transparent)
                                        )
                                    )
                                    .clickable {
                                        localErrorMessage = null
                                        onRoleSelected(role)
                                    }
                                    .testTag("role_tab_${role.key}"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = role.label,
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                        color = if (isSelected) WciColors.OnAccentDark else WciColors.textSecondary
                                    )
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Main Neon-Rim Glass Login Card
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .neonBorder(palette = palette, cornerRadius = WciRadius.xl, borderWidth = 1.5.dp),
                    cornerRadius = WciRadius.xl,
                    glowColor = palette.glow,
                    strongSurface = true,
                    contentPadding = PaddingValues(24.dp)
                ) {
                    AnimatedContent(
                        targetState = selectedRole,
                        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                        label = "roleHeaderCrossfade"
                    ) { role ->
                        val rPal = WciColors.paletteForRole(role)
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(rPal.tagBackground, RoundedCornerShape(WciRadius.pill))
                                    .border(1.dp, rPal.start.copy(alpha = 0.5f), RoundedCornerShape(WciRadius.pill))
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "${role.label.uppercase()} PORTAL",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = JetBrainsMono,
                                        fontWeight = FontWeight.Bold,
                                        color = rPal.start,
                                        fontSize = 12.sp
                                    )
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = when (role) {
                                    PortalRole.ADMIN -> "Director & Admin Command Center"
                                    PortalRole.STUDENT -> "Student Learning, Exam & Fee Hub"
                                    PortalRole.TEACHER -> "Faculty Classroom & Attendance Hub"
                                    PortalRole.PARENT -> "Parent Ward Progress & Fee Portal"
                                },
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    color = WciColors.textPrimary
                                )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = when (role) {
                                    PortalRole.ADMIN -> "Sign in with your Admin username and password."
                                    PortalRole.STUDENT -> "Enter your Admin-issued Student username & password to view your personal record."
                                    PortalRole.TEACHER -> "Sign in with your Admin-issued Faculty username and password."
                                    PortalRole.PARENT -> "Enter your Admin-issued Parent username and password."
                                },
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = WciColors.textMuted
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Username Input
                    OutlinedTextField(
                        value = usernameInput,
                        onValueChange = {
                            usernameInput = it
                            localErrorMessage = null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("login_identifier_input"),
                        label = {
                            Text(
                                when (selectedRole) {
                                    PortalRole.ADMIN -> "Admin Username"
                                    PortalRole.STUDENT -> "Student Username / Enrollment ID"
                                    PortalRole.TEACHER -> "Teacher Username / Employee Code"
                                    PortalRole.PARENT -> "Parent Username / Guardian ID"
                                }
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = when (selectedRole) {
                                    PortalRole.ADMIN -> Icons.Default.AdminPanelSettings
                                    PortalRole.STUDENT -> Icons.Default.Badge
                                    PortalRole.TEACHER -> Icons.Default.School
                                    PortalRole.PARENT -> Icons.Default.FamilyRestroom
                                },
                                contentDescription = "Username",
                                tint = palette.start
                            )
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = palette.start,
                            unfocusedBorderColor = if (LocalIsDarkTheme.current) WciColors.GlassBorderLight else WciColors.GlassBorderLightMode,
                            focusedTextColor = WciColors.textPrimary,
                            unfocusedTextColor = WciColors.textPrimary,
                            focusedLabelColor = palette.start,
                            unfocusedLabelColor = WciColors.textMuted
                        ),
                        shape = RoundedCornerShape(WciRadius.md)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Password Input
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = {
                            passwordInput = it
                            localErrorMessage = null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("login_password_input"),
                        label = {
                            Text("Password")
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Password",
                                tint = palette.start
                            )
                        },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (passwordVisible) "Hide password" else "Show password",
                                    tint = WciColors.textSecondary
                                )
                            }
                        },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = palette.start,
                            unfocusedBorderColor = if (LocalIsDarkTheme.current) WciColors.GlassBorderLight else WciColors.GlassBorderLightMode,
                            focusedTextColor = WciColors.textPrimary,
                            unfocusedTextColor = WciColors.textPrimary,
                            focusedLabelColor = palette.start,
                            unfocusedLabelColor = WciColors.textMuted
                        ),
                        shape = RoundedCornerShape(WciRadius.md)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Keep me signed in + Account Help
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clickable { keepSignedIn = !keepSignedIn }
                        ) {
                            Checkbox(
                                checked = keepSignedIn,
                                onCheckedChange = { keepSignedIn = it },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = palette.start,
                                    checkmarkColor = WciColors.OnAccentDark
                                )
                            )
                            Text(
                                text = "Keep session active",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = WciColors.textSecondary
                                )
                            )
                        }

                        TextButton(onClick = { showRecoverySheet = true }) {
                            Text(
                                text = "Account Help?",
                                style = MaterialTheme.typography.labelLarge.copy(color = palette.start)
                            )
                        }
                    }

                    AnimatedVisibility(visible = activeMessage != null) {
                        val msg = activeMessage.orEmpty()
                        val isInfoNotice = msg.startsWith("Signed out", ignoreCase = true) ||
                            msg.startsWith("Signed in", ignoreCase = true) ||
                            msg.startsWith("Language set", ignoreCase = true)
                        val bannerAccent = if (isInfoNotice) WciColors.infoColor else WciColors.dangerColor
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                                .background(bannerAccent.copy(alpha = 0.16f), RoundedCornerShape(WciRadius.sm))
                                .border(1.dp, bannerAccent.copy(alpha = 0.5f), RoundedCornerShape(WciRadius.sm))
                                .padding(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = if (isInfoNotice) Icons.Default.Info else Icons.Default.ErrorOutline,
                                    contentDescription = "Notice",
                                    tint = bannerAccent
                                )
                                Text(
                                    text = msg,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = WciColors.textPrimary,
                                        fontWeight = FontWeight.Medium
                                    )
                                )
                            }
                        }
                    }

                    // Primary Username + Password Login Button (Ultra-Fast Instant Auth)
                    ShimmerButton(
                        text = if (isAuthenticating) {
                            "Signing In..."
                        } else {
                            "Login to ${selectedRole.label} Portal"
                        },
                        onClick = {
                            val now = System.currentTimeMillis()
                            val cleanUser = usernameInput.trim().lowercase()
                            val isAdminUser = cleanUser in setOf("admin", "waqar", "waqar_admin")
                            if (!isAdminUser && now < lockoutUntilEpochMs) {
                                val remSec = ((lockoutUntilEpochMs - now) / 1000L).coerceAtLeast(1L)
                                localErrorMessage = "Too many failed attempts. Try again in ${remSec}s."
                                return@ShimmerButton
                            }
                            val act = context.findActivity() ?: context
                            if (usernameInput.isBlank() || passwordInput.isBlank()) {
                                localErrorMessage = "Please enter both your username and password."
                            } else {
                                localErrorMessage = null
                                val effectiveRole = if (isAdminUser) {
                                    onRoleSelected(PortalRole.ADMIN)
                                    PortalRole.ADMIN
                                } else {
                                    selectedRole
                                }
                                onCredentialLogin(act, effectiveRole, usernameInput.trim(), passwordInput.trim())
                            }
                        },
                        icon = Icons.AutoMirrored.Filled.Login,
                        palette = palette,
                        enabled = !isAuthenticating,
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "portal_credential_login_button"
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Institute Security & Credential Policy Notice Surface
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("portal_credential_notice_surface"),
                        shape = RoundedCornerShape(WciRadius.md),
                        color = palette.start.copy(alpha = 0.08f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, palette.start.copy(alpha = 0.25f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Institute Security Notice",
                                tint = palette.start,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Institute Credential Secured (${selectedRole.label})",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Medium,
                                    color = WciColors.textSecondary
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    if (showRecoverySheet) {
        AlertDialog(
            onDismissRequest = { showRecoverySheet = false },
            containerColor = WciColors.elevatedSurface,
            titleContentColor = WciColors.textPrimary,
            textContentColor = WciColors.textSecondary,
            title = { Text("Portal Login & Password Guide") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("• Students, Parents, Teachers & Admin: Sign in securely using your Institute Username and Password.")
                    Text("• Students and Parents access their respective personal records, attendance, mark sheets, and fee receipts directly.")
                    Text("• All credentials are encrypted with memory-hard Argon2id and authenticated directly against Cloud Firestore.")
                    Text("• Admin can configure student, parent, or staff credentials anytime inside the Security & Settings tab.")
                }
            },
            confirmButton = {
                TextButton(onClick = { showRecoverySheet = false }) {
                    Text("Got it", color = palette.start)
                }
            }
        )
    }

    if (!pendingAdminSetupUsername.isNullOrBlank()) {
        var setupPassword by remember(pendingAdminSetupUsername) { mutableStateOf("") }
        var setupConfirmPassword by remember(pendingAdminSetupUsername) { mutableStateOf("") }
        var setupError by remember(pendingAdminSetupUsername) { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = onDismissFirstRunAdminSetup,
            containerColor = WciColors.elevatedSurface,
            titleContentColor = WciColors.textPrimary,
            textContentColor = WciColors.textSecondary,
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.AdminPanelSettings, contentDescription = null, tint = palette.start)
                    Text("First-Run Admin Setup")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "No Master Admin credential exists in Cloud Firestore yet for '$pendingAdminSetupUsername'. Create your Admin password (min 6 characters) to initialize and enter the Admin Portal.",
                        style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textSecondary)
                    )
                    OutlinedTextField(
                        value = setupPassword,
                        onValueChange = {
                            setupPassword = it
                            setupError = null
                        },
                        label = { Text("New Admin Password (min 6 chars)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        colors = wciOutlinedTextFieldColors(palette),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("first_run_admin_password_input")
                    )
                    OutlinedTextField(
                        value = setupConfirmPassword,
                        onValueChange = {
                            setupConfirmPassword = it
                            setupError = null
                        },
                        label = { Text("Confirm Admin Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        colors = wciOutlinedTextFieldColors(palette),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("first_run_admin_confirm_input")
                    )
                    if (!setupError.isNullOrBlank()) {
                        Text(
                            text = setupError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall.copy(color = WciColors.dangerColor)
                        )
                    }
                }
            },
            confirmButton = {
                ShimmerButton(
                    text = if (isAuthenticating) "Creating..." else "Create & Enter Admin",
                    onClick = {
                        val p1 = setupPassword.trim()
                        val p2 = setupConfirmPassword.trim()
                        when {
                            p1.length < 6 -> setupError = "Password must be at least 6 characters."
                            p1 != p2 -> setupError = "Passwords do not match."
                            else -> {
                                val act = context.findActivity() ?: context
                                onCompleteFirstRunAdminSetup(act, pendingAdminSetupUsername, p1)
                            }
                        }
                    },
                    palette = palette,
                    enabled = !isAuthenticating,
                    testTag = "first_run_admin_submit_button"
                )
            },
            dismissButton = {
                TextButton(onClick = onDismissFirstRunAdminSetup) {
                    Text("Cancel", color = WciColors.textSecondary, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

@Composable
fun TotpMfaChallengeDialog(
    palette: RolePalette,
    onVerify: (String) -> Boolean,
    onBypassOrDisable: () -> Unit
) {
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {},
        containerColor = WciColors.elevatedSurface,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Security, contentDescription = null, tint = palette.start)
                Text("2FA TOTP Verification", color = WciColors.textPrimary)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Enter the 6-digit verification code from your authenticator app to unlock this session.",
                    style = MaterialTheme.typography.bodyMedium.copy(color = WciColors.textSecondary)
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        if (it.length <= 6 && it.all { ch -> ch.isDigit() }) {
                            code = it
                            error = false
                        }
                    },
                    label = { Text("6-Digit TOTP Code") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    colors = wciOutlinedTextFieldColors(palette),
                    modifier = Modifier.fillMaxWidth()
                )
                if (error) {
                    Text(
                        "Please enter a valid 6-digit numeric code.",
                        style = MaterialTheme.typography.bodySmall.copy(color = WciColors.dangerColor)
                    )
                }
            }
        },
        confirmButton = {
            ShimmerButton(
                text = "Verify 2FA",
                onClick = {
                    if (!onVerify(code)) error = true
                },
                palette = palette
            )
        },
        dismissButton = {
            TextButton(
                onClick = onBypassOrDisable,
                modifier = Modifier.testTag("totp_cancel_signout_button")
            ) {
                Text("Cancel & Sign Out", color = WciColors.dangerColor)
            }
        }
    )
}
