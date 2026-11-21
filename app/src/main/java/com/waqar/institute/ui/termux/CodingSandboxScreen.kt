package com.waqar.institute.ui.termux

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.designsystem.*
import com.waqar.institute.terminal.CodingSandboxUiState
import com.waqar.institute.terminal.CodingSandboxViewModel
import com.waqar.institute.terminal.PreviewViewportMode
import com.waqar.institute.terminal.SandboxLanguage
import java.io.ByteArrayInputStream

/**
 * 100% Offline Coding, Testing & Learning Skill Screen
 * (`CodingSandboxScreen.kt` — कोडिंग, टेस्टिंग और लर्निंग स्किल).
 *
 * Features:
 * 1. Multi-language offline code editor (Python 3, JavaScript ES6, HTML5/CSS3/JS).
 * 2. Responsive Dual-Mode Live Preview Window:
 *    - Compact Mobile-Frame Preview (320.dp mobile bezel frame for console/app preview).
 *    - Wide Desktop / Web Preview (full-width browser frame with offline-jailed WebView).
 * 3. 100-Lakh Brahmastra Sandbox Security & Anti-Hacking Banner:
 *    - Displays a permanent red security revocation banner and disables all editor/execution
 *      controls when `codingAccessLocked == true`.
 */
@Composable
fun CodingSandboxScreen(
    activeUserId: String,
    activeUsername: String,
    activePortalRole: String,
    palette: RolePalette,
    sandboxViewModel: CodingSandboxViewModel = viewModel()
) {
    val context = LocalContext.current
    val state by sandboxViewModel.uiState.collectAsState()

    LaunchedEffect(activeUserId, activeUsername, activePortalRole) {
        sandboxViewModel.bindUserSession(
            context = context,
            userId = activeUserId,
            username = activeUsername,
            portalRole = activePortalRole
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("coding_sandbox_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Permanent Red Security Violation Banner when codingAccessLocked == true
        if (state.codingAccessLocked) {
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = WciColors.SandboxDangerBg,
                    border = BorderStroke(2.dp, WciColors.ScannerRed),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("coding_sandbox_revoked_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Coding Access Revoked",
                            tint = WciColors.BiometricCoralAlert,
                            modifier = Modifier.size(32.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = state.securityLockoutBanner
                                    ?: CodingSandboxUiState.PERMANENT_REVOCATION_BANNER,
                                color = WciColors.SandboxDangerText,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.sp,
                                lineHeight = 19.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Brahmastra Sandbox Intrusion Prevention System • Incident logged to Firestore Audit Trail",
                                color = WciColors.BiometricCoralAlert,
                                fontFamily = JetBrainsMono,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }

        // 2. Header Card with Security Shield Status
        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                glowColor = if (state.codingAccessLocked) WciColors.Danger else palette.start,
                strongSurface = true
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Coding, Testing & Learning Lab",
                            style = MaterialTheme.typography.titleLarge.copy(
                                color = WciColors.textPrimary,
                                fontWeight = FontWeight.ExtraBold
                            )
                        )
                        Text(
                            text = "100% Offline Coding Lab • Python Evaluator • JavaScript Evaluator • HTML5/CSS3 • 3s Watchdog & AST Security Shield",
                            style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textSecondary)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = if (state.codingAccessLocked) {
                            WciColors.Danger.copy(alpha = 0.2f)
                        } else {
                            WciColors.Success.copy(alpha = 0.18f)
                        },
                        border = BorderStroke(
                            1.dp,
                            if (state.codingAccessLocked) WciColors.Danger else WciColors.Success
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = "Sandbox Status",
                                tint = if (state.codingAccessLocked) WciColors.Danger else WciColors.Success,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = if (state.codingAccessLocked) "LOCKED" else "JAILED OFFLINE",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = JetBrainsMono,
                                    fontWeight = FontWeight.Bold,
                                    color = if (state.codingAccessLocked) WciColors.Danger else WciColors.Success
                                )
                            )
                        }
                    }
                }
            }
        }

        // 3. Language Selector & Dual-Mode Preview Viewport Switcher
        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "1. Select Offline Language Engine",
                        style = MaterialTheme.typography.labelLarge.copy(
                            color = WciColors.textSecondary,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SandboxLanguage.entries.forEach { lang ->
                            LiquidPill(
                                text = lang.label,
                                selected = state.selectedLanguage == lang,
                                icon = Icons.Default.Code,
                                modifier = Modifier.weight(1f),
                                onClick = { sandboxViewModel.selectLanguage(lang) }
                            )
                        }
                    }

                    Text(
                        text = "2. Responsive Dual-Mode Live Preview Mode",
                        style = MaterialTheme.typography.labelLarge.copy(
                            color = WciColors.textSecondary,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LiquidPill(
                            text = PreviewViewportMode.COMPACT_MOBILE.label,
                            selected = state.previewMode == PreviewViewportMode.COMPACT_MOBILE,
                            icon = Icons.Default.PhoneAndroid,
                            modifier = Modifier.weight(1f),
                            onClick = { sandboxViewModel.togglePreviewMode(PreviewViewportMode.COMPACT_MOBILE) }
                        )
                        LiquidPill(
                            text = PreviewViewportMode.DESKTOP_WEB.label,
                            selected = state.previewMode == PreviewViewportMode.DESKTOP_WEB,
                            icon = Icons.Default.DesktopWindows,
                            modifier = Modifier.weight(1f),
                            onClick = { sandboxViewModel.togglePreviewMode(PreviewViewportMode.DESKTOP_WEB) }
                        )
                    }
                }
            }
        }

        // 4. Code Editor Card
        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                glowColor = palette.end
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = null,
                                tint = palette.start
                            )
                            Text(
                                text = "main.${state.selectedLanguage.extension} (${state.selectedLanguage.label})",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontFamily = JetBrainsMono,
                                    color = WciColors.textPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                        if (state.lastExecutionDurationMs > 0L) {
                            Text(
                                text = "${state.lastExecutionDurationMs}ms • ${state.memoryUsedKb} KB",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = JetBrainsMono,
                                    color = WciColors.Success
                                )
                            )
                        }
                    }

                    OutlinedTextField(
                        value = state.activeCode,
                        onValueChange = { sandboxViewModel.updateCode(it) },
                        enabled = !state.codingAccessLocked && !state.isExecuting,
                        textStyle = TextStyle(
                            fontFamily = JetBrainsMono,
                            fontSize = 13.sp,
                            color = WciColors.SlateTrack,
                            lineHeight = 19.sp
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 210.dp, max = 340.dp)
                            .testTag("coding_sandbox_editor_input"),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = palette.start,
                            unfocusedBorderColor = WciColors.GlassBorderLight,
                            disabledBorderColor = WciColors.Danger.copy(alpha = 0.6f),
                            focusedContainerColor = WciColors.SandboxEditorBg,
                            unfocusedContainerColor = WciColors.SandboxEditorBg,
                            disabledContainerColor = WciColors.SandboxEditorDisabledBg
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ShimmerButton(
                            text = when {
                                state.codingAccessLocked -> "Access Permanently Revoked"
                                state.isExecuting -> "Running on Dispatchers.IO…"
                                else -> "Run Code (Offline Sandbox)"
                            },
                            icon = if (state.codingAccessLocked) Icons.Default.Lock else Icons.Default.PlayArrow,
                            enabled = !state.codingAccessLocked && !state.isExecuting,
                            onClick = { sandboxViewModel.executeCurrentCode(context) },
                            modifier = Modifier.weight(1f),
                            testTag = "coding_sandbox_run_button"
                        )

                        OutlinedButton(
                            onClick = { sandboxViewModel.clearConsole() },
                            enabled = !state.codingAccessLocked && !state.isExecuting,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, WciColors.GlassBorderLight),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("coding_sandbox_clear_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "Clear Console",
                                tint = WciColors.textSecondary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Clear", color = WciColors.textSecondary)
                        }
                    }
                }
            }
        }

        // 5. Responsive Dual-Mode Live Preview Window
        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                glowColor = palette.start,
                strongSurface = true
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (state.previewMode == PreviewViewportMode.COMPACT_MOBILE) {
                                "Compact Mobile Frame Preview (Console / App Output)"
                            } else {
                                "Wide Desktop / Web Live Preview (Offline HTML5/CSS3/JS)"
                            },
                            style = MaterialTheme.typography.titleSmall.copy(
                                color = WciColors.textPrimary,
                                fontWeight = FontWeight.ExtraBold
                            )
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(WciColors.ScannerRed)
                            )
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(WciColors.AmberWarm)
                            )
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(WciColors.ScannerGreen)
                            )
                        }
                    }

                    val frameModifier = if (state.previewMode == PreviewViewportMode.COMPACT_MOBILE) {
                        Modifier
                            .widthIn(max = 340.dp)
                            .fillMaxWidth()
                            .border(
                                width = 3.dp,
                                brush = Brush.linearGradient(listOf(palette.start, palette.end)),
                                shape = RoundedCornerShape(24.dp)
                            )
                            .clip(RoundedCornerShape(24.dp))
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .border(
                                width = 1.5.dp,
                                color = WciColors.GlassBorderLight,
                                shape = RoundedCornerShape(16.dp)
                            )
                            .clip(RoundedCornerShape(16.dp))
                    }

                    Box(
                        modifier = frameModifier
                            .background(WciColors.SandboxTerminalBg)
                            .padding(12.dp)
                    ) {
                        if (state.selectedLanguage == SandboxLanguage.HTML_CSS_JS && !state.codingAccessLocked) {
                            OfflineSandboxedHtmlWebView(
                                htmlContent = state.renderedHtmlDocument,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(260.dp)
                            )
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 160.dp)
                            ) {
                                Text(
                                    text = "$ wci-sandbox --offline --watchdog=3000ms",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = JetBrainsMono,
                                        color = WciColors.Warning
                                    )
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = state.terminalOutput,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = JetBrainsMono,
                                        color = if (state.codingAccessLocked) {
                                            WciColors.BiometricCoralAlert
                                        } else {
                                            WciColors.MintTextSoft
                                        },
                                        lineHeight = 18.sp
                                    ),
                                    modifier = Modifier.testTag("coding_sandbox_terminal_output")
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun OfflineSandboxedHtmlWebView(
    htmlContent: String,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    blockNetworkLoads = true
                    blockNetworkImage = true
                    cacheMode = WebSettings.LOAD_NO_CACHE
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val url = request?.url?.toString().orEmpty()
                        // Strictly block all external HTTP/HTTPS/file requests inside the offline sandbox
                        if (!url.startsWith("about:blank") && !url.startsWith("data:")) {
                            return WebResourceResponse(
                                "text/plain",
                                "UTF-8",
                                ByteArrayInputStream("Blocked by WCI Offline Sandbox".toByteArray())
                            )
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(
                "about:blank",
                htmlContent,
                "text/html",
                "UTF-8",
                null
            )
        }
    )
}
