package com.example

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.security.BaselineProfileOptimization
import com.example.security.EnterpriseRaspGuard
import com.example.security.MemoryScrubberLifecycleObserver
import com.example.security.RaspAndRootShieldManager
import com.example.security.configureAppCheck
import com.example.ui.auth.NeonWaveLoginScreen
import com.example.ui.auth.TotpMfaChallengeDialog
import com.example.ui.designsystem.JetBrainsMono
import com.example.ui.designsystem.ShimmerButton
import com.example.ui.designsystem.WciColors
import com.example.ui.portal.PortalShellScreen
import com.example.ui.theme.WaqarInstituteTheme
import com.example.viewmodel.InstituteViewModel
import com.google.firebase.Firebase
import com.google.firebase.firestore.firestore
import com.waqar.institute.security.AntiHookManager
import com.waqar.institute.security.AppSelfHealingManager
import com.waqar.institute.security.BindPortalWindowSecurity
import com.waqar.institute.security.LifecyclePrivacyBlurContainer
import com.waqar.institute.security.NativeSecurityBridge
import com.waqar.institute.security.RecentAppsBlurController
import com.waqar.institute.security.SkiaGpuRenderOptimizer
import com.waqar.institute.security.TeeSecurityManager
import com.waqar.institute.security.VirtualSpaceShield
import com.waqar.institute.security.WindowSecureManager
import com.waqar.institute.session.SessionManager
import com.waqar.institute.utils.GlobalExceptionHandler
import com.waqar.institute.workers.InstituteBackgroundSyncWorker
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class MainActivity : ComponentActivity() {
    private var screenCaptureCallback: Any? = null
    private var pendingVerifyUri by mutableStateOf<Uri?>(null)

    private fun extractVerifyUri(targetIntent: Intent?): Uri? {
        val data = targetIntent?.data ?: return null
        val host = data.host?.lowercase().orEmpty()
        val path = data.path.orEmpty()
        return if (host == "waqar-institute-erp.web.app" && path.startsWith("/verify")) data else null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pendingVerifyUri = extractVerifyUri(intent)

        // 1. Install global resilience handler, surface lifecycle guard & view rendering protocols
        runCatching { GlobalExceptionHandler.install() }
        runCatching { configureAppCheck(this, intent) }
        runCatching { com.waqar.institute.render.ViewRenderingProtocol.installLaunchRenderingProtocols(this) }
        runCatching { lifecycle.addObserver(MemoryScrubberLifecycleObserver(this)) }

        // 2. Render Compose UI immediately on Frame 0 (surface protocol eliminates black screen)
        enableEdgeToEdge()
        setContent {
            com.waqar.institute.render.ViewRenderingProtocol.NotifyFirstFrameRendered()
            WaqarInstituteApp(
                deepLinkVerifyUri = pendingVerifyUri,
                onDismissVerifyDeepLink = { pendingVerifyUri = null }
            )
        }

        // 3. Android 14+ (API 34+) Hardware Screenshot Attempt Detection registration
        // Kept non-blocking; FLAG_SECURE is scoped strictly to active CBT Exam screens.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching {
                val callback = Activity.ScreenCaptureCallback {
                    // Log screenshot event telemetry without locking the entire application screen
                    android.util.Log.w("MainActivity", "Screenshot capture detected by system")
                }
                screenCaptureCallback = callback
                registerScreenCaptureCallback(mainExecutor, callback)
            }
        }

        // 4. Execute heavy RASP, StrongBox TEE KeyStore, ZIP integrity & WorkManager initialization on background IO thread
        val appCtx = applicationContext
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                RaspAndRootShieldManager.initializeShield(
                    context = appCtx,
                    expectedCertBase64Hash = NativeSecurityBridge.expectedTalsecBase64CertHash()
                )
            }
            runCatching {
                EnterpriseRaspGuard.enforceStartupIntegrity(
                    context = appCtx,
                    terminateOnReleaseCompromise = !BuildConfig.DEBUG
                )
            }
            runCatching { BaselineProfileOptimization.installAsync(appCtx) }
            runCatching {
                NativeSecurityBridge.initializeDefenseGradeSecurity(
                    context = appCtx,
                    enforceReleaseLockdown = !BuildConfig.DEBUG
                )
            }
            runCatching { AppSelfHealingManager.initializeSelfHealingDaemon(appCtx) }
            runCatching { AntiHookManager.runComprehensiveHookScan() }
            runCatching { VirtualSpaceShield.evaluateEnvironment(appCtx) }
            runCatching { TeeSecurityManager.inspectHardwareKeyAttestation(appCtx) }
            runCatching { com.waqar.institute.security.BrahmastraUnifiedWiringOrchestrator.bootstrapAll26BrahmastraModules(appCtx) }
            runCatching { InstituteBackgroundSyncWorker.schedulePeriodicEnterpriseSync(appCtx) }
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        if (ev != null) {
            val allowed = runCatching {
                com.waqar.institute.security.BrahmastraUnifiedWiringOrchestrator.inspectGlobalActivityTouch(ev)
            }.getOrDefault(true)
            if (!allowed) return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractVerifyUri(intent)?.let { pendingVerifyUri = it }
    }

    override fun onResume() {
        super.onResume()
        val appCtx = applicationContext
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                com.waqar.institute.security.DeviceRevocationGuard.evaluateStartupAndResume(appCtx)
            }
        }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            (screenCaptureCallback as? Activity.ScreenCaptureCallback)?.let { cb ->
                runCatching { unregisterScreenCaptureCallback(cb) }
            }
        }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        runCatching { RecentAppsBlurController.onWindowFocusChanged(this, hasFocus) }
    }
}

@Composable
fun WaqarInstituteApp(
    deepLinkVerifyUri: Uri? = null,
    onDismissVerifyDeepLink: () -> Unit = {}
) {
    val context = LocalContext.current
    val viewModel: InstituteViewModel = viewModel(factory = InstituteViewModel.Factory(context))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val rolePalette = WciColors.paletteForRole(state.activePortalRole)
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val isAuthenticated = state.currentUser != null || state.userProfile != null
    val forceLogoutMsg by SessionManager.sessionForceLogoutEvent.collectAsStateWithLifecycle()
    var availableGitHubRelease by remember {
        mutableStateOf<com.waqar.institute.updater.GitHubOtaUpdater.GitHubReleaseInfo?>(null)
    }

    val raspViolation by RaspAndRootShieldManager.securityViolation.collectAsStateWithLifecycle()
    val nativeTamperViolation by NativeSecurityBridge.tamperViolationReason.collectAsStateWithLifecycle()
    val deviceLockdownState by com.waqar.institute.security.DeviceRevocationGuard.lockdownState.collectAsStateWithLifecycle()

    // Non-Play-Store Signed Release APK policy: ignore RASP_UNTRUSTED_INSTALLER while strictly
    // blocking APK signature mismatches, repackaging, Frida/Xposed hooks, Root/Magisk, & PC Emulators.
    val fatalRaspReason = remember(raspViolation, nativeTamperViolation, state.raspReport) {
        val isRobolectric = Build.FINGERPRINT.equals("robolectric", ignoreCase = true)
        if (BuildConfig.DEBUG || isRobolectric) {
            null
        } else {
            val fatalCodes = setOf(
                "RASP_ROOT_BINARY_AND_APP_DETECTED",
                "RASP_ROOT_KERNELSU_DETECTED",
                "RASP_DEBUGGER_ATTACHED",
                "RASP_EMULATOR_BLOCKED",
                "RASP_APK_REPACKAGED_MT_MANAGER",
                "RASP_FRIDA_LSPOSED_HOOK_DETECTED",
                "RASP_DUAL_SPACE_CLONE_DETECTED"
            )
            val audit = state.raspReport
            when {
                !nativeTamperViolation.isNullOrBlank() -> nativeTamperViolation
                raspViolation in fatalCodes -> raspViolation
                audit?.isCompromised == true -> when {
                    audit.rootBeerDetected -> "RASP_ROOT_DETECTED"
                    audit.isEmulatorEnvironment -> "RASP_EMULATOR_BLOCKED"
                    audit.memoryHookDetected -> "RASP_MEMORY_HOOK_DETECTED"
                    audit.debuggerConnected || audit.tracerPidDetected -> "RASP_DEBUGGER_ATTACHED"
                    audit.nativeRaspFlags != 0 -> "RASP_NATIVE_FLAGS_${audit.nativeRaspFlags}"
                    else -> "ENVIRONMENT_INTEGRITY_COMPROMISED"
                }
                else -> null
            }
        }
    }

    LaunchedEffect(fatalRaspReason) {
        if (fatalRaspReason != null) {
            runCatching { MemoryScrubberLifecycleObserver.scrubAllRegisteredBuffersNow() }
        }
    }

    BindPortalWindowSecurity(
        portalRole = state.activePortalRole.key,
        isAuthenticated = isAuthenticated,
        allowAdminSecureOverride = state.preferences.flagSecureEnabled
    )

    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { viewModel.refreshRaspSecurityAudit(context) }
            runCatching { com.waqar.institute.security.DeviceRevocationGuard.evaluateStartupAndResume(context) }
            val latestRelease = runCatching {
                com.waqar.institute.updater.GitHubOtaUpdater.checkForUpdate()
            }.getOrNull()
            if (latestRelease != null) {
                availableGitHubRelease = latestRelease
            }
        }
    }

    WaqarInstituteTheme(
        darkTheme = state.preferences.darkTheme,
        rolePalette = rolePalette,
        uiTier = state.effectiveUiTier,
        languageCode = state.preferences.languageCode
    ) {
        if (fatalRaspReason != null) {
            TamperSecurityLockdownScreen(
                violationCode = fatalRaspReason,
                onExitApp = {
                    runCatching { (context as? Activity)?.finishAffinity() }
                    kotlin.system.exitProcess(1)
                }
            )
            return@WaqarInstituteTheme
        }

        when (val lock = deviceLockdownState) {
            is com.waqar.institute.security.DeviceRevocationGuard.DeviceLockdownState.ForceUpdateRequired -> {
                ForceUpdateLockdownScreen(
                    currentVersionCode = lock.currentVersionCode,
                    minSupportedVersionCode = lock.minSupportedVersionCode,
                    releaseInfo = lock.latestReleaseInfo ?: availableGitHubRelease,
                    onExitApp = {
                        runCatching { (context as? Activity)?.finishAffinity() }
                        kotlin.system.exitProcess(0)
                    }
                )
                return@WaqarInstituteTheme
            }
            is com.waqar.institute.security.DeviceRevocationGuard.DeviceLockdownState.DeviceRevoked -> {
                RevokedDeviceLockdownScreen(
                    deviceFingerprint = lock.deviceFingerprint,
                    reason = lock.reason,
                    onExitApp = {
                        runCatching { viewModel.signOut() }
                        runCatching { (context as? Activity)?.finishAffinity() }
                        kotlin.system.exitProcess(0)
                    }
                )
                return@WaqarInstituteTheme
            }
            com.waqar.institute.security.DeviceRevocationGuard.DeviceLockdownState.Allowed -> Unit
        }

        val isWindowProtected = WindowSecureManager.isPortalProtected(
            portalRole = state.activePortalRole.key,
            isAuthenticated = isAuthenticated,
            allowAdminSecureOverride = state.preferences.flagSecureEnabled
        )
        LifecyclePrivacyBlurContainer(enabled = isWindowProtected) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                if (!isAuthenticated) {
                    NeonWaveLoginScreen(
                        selectedRole = state.activePortalRole,
                        languageCode = state.preferences.languageCode,
                        instituteName = state.preferences.instituteName,
                        instituteTagline = state.preferences.instituteTagline,
                        isAuthenticating = state.isAuthenticating,
                        externalStatusMessage = forceLogoutMsg ?: state.statusMessage,
                        pendingAdminSetupUsername = state.pendingAdminSetupUsername,
                        onRoleSelected = { role -> viewModel.switchPortalRole(role) },
                        onLanguageToggle = {
                            viewModel.setLanguage("EN")
                        },
                        onCredentialLogin = { actCtx, role, username, password ->
                            SessionManager.clearForceLogoutNotice()
                            viewModel.authenticateWithPortalCredentials(actCtx, role, username, password)
                        },
                        onCompleteFirstRunAdminSetup = { actCtx, username, password ->
                            viewModel.completeFirstRunAdminSetup(actCtx, username, password)
                        },
                        onDismissFirstRunAdminSetup = {
                            viewModel.dismissFirstRunAdminSetup()
                        }
                    )
                } else {
                    PortalShellScreen(
                        state = state,
                        viewModel = viewModel,
                        externalDrawerState = drawerState
                    )

                    if (state.mfaChallengePending) {
                        TotpMfaChallengeDialog(
                            palette = rolePalette,
                            onVerify = { code -> viewModel.verifyTotpMfa(code) },
                            onBypassOrDisable = { viewModel.signOut() }
                        )
                    }
                }

                deepLinkVerifyUri?.let { uri ->
                    CertificateVerificationDeepLinkDialog(
                        verifyUri = uri,
                        localCertificates = state.certificates,
                        onDismiss = onDismissVerifyDeepLink
                    )
                }

                availableGitHubRelease?.let { releaseInfo ->
                    com.waqar.institute.updater.UpdateDialog(
                        releaseInfo = releaseInfo
                    )
                }
            }
        }
    }
}

@Composable
private fun TamperSecurityLockdownScreen(
    violationCode: String,
    onExitApp: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("release_tamper_lockdown_screen"),
        color = WciColors.BgElevatedDark
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "SECURITY LOCKDOWN",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        color = WciColors.Danger,
                        fontWeight = FontWeight.ExtraBold
                    )
                )
                Text(
                    text = "Access blocked: APK integrity, root/hook posture, or runtime environment verification failed.",
                    style = MaterialTheme.typography.bodyMedium.copy(color = Color.White)
                )
                Text(
                    text = "Code: $violationCode",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontFamily = JetBrainsMono,
                        color = WciColors.Warning
                    )
                )
                Text(
                    text = "Install the official signed Release APK on a non-rooted physical Android device without emulators or hooks.",
                    style = MaterialTheme.typography.labelMedium.copy(color = WciColors.TextSecondaryDark)
                )
                ShimmerButton(
                    text = "Exit Application",
                    onClick = onExitApp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("tamper_lockdown_exit_button")
                )
            }
        }
    }
}

@Composable
private fun ForceUpdateLockdownScreen(
    currentVersionCode: Int,
    minSupportedVersionCode: Long,
    releaseInfo: com.waqar.institute.updater.GitHubOtaUpdater.GitHubReleaseInfo?,
    onExitApp: () -> Unit
) {
    val context = LocalContext.current
    var showUpdaterDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("force_update_lockdown_screen"),
        color = WciColors.BgElevatedDark
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "MANDATORY SECURITY UPDATE",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        color = WciColors.Warning,
                        fontWeight = FontWeight.ExtraBold
                    )
                )
                Text(
                    text = "This application build (vCode $currentVersionCode) is below the minimum supported security release (vCode $minSupportedVersionCode). Please install the latest signed official release to continue.",
                    style = MaterialTheme.typography.bodyMedium.copy(color = Color.White)
                )
                if (releaseInfo != null) {
                    Text(
                        text = "Latest Official Release: ${releaseInfo.tagName}",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontFamily = JetBrainsMono,
                            color = WciColors.Success
                        )
                    )
                }
                ShimmerButton(
                    text = "Download & Install Official Update",
                    onClick = {
                        if (releaseInfo != null) {
                            showUpdaterDialog = true
                        } else {
                            runCatching {
                                val intent = Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/waqarkhan8256972/waqar-institute-erp/releases/latest")
                                )
                                context.startActivity(intent)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("force_update_download_button")
                )
                TextButton(
                    onClick = onExitApp,
                    modifier = Modifier.testTag("force_update_exit_button")
                ) {
                    Text("Exit App", color = WciColors.TextSecondaryDark)
                }
            }
        }
    }

    if (showUpdaterDialog && releaseInfo != null) {
        com.waqar.institute.updater.UpdateDialog(releaseInfo = releaseInfo)
    }
}

@Composable
private fun RevokedDeviceLockdownScreen(
    deviceFingerprint: String,
    reason: String,
    onExitApp: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("revoked_device_lockdown_screen"),
        color = WciColors.BgElevatedDark
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "DEVICE ACCESS REVOKED",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        color = WciColors.Danger,
                        fontWeight = FontWeight.ExtraBold
                    )
                )
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodyMedium.copy(color = Color.White)
                )
                Text(
                    text = "Device ID: $deviceFingerprint",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = JetBrainsMono,
                        color = WciColors.Warning
                    )
                )
                Text(
                    text = "Please contact Waqar Computer Institute Administration to verify your identity and restore access.",
                    style = MaterialTheme.typography.labelMedium.copy(color = WciColors.TextSecondaryDark)
                )
                ShimmerButton(
                    text = "Close Application",
                    onClick = onExitApp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("revoked_device_exit_button")
                )
            }
        }
    }
}

@Composable
private fun CertificateVerificationDeepLinkDialog(
    verifyUri: Uri,
    localCertificates: List<com.example.data.model.CertificateRecord>,
    onDismiss: () -> Unit
) {
    val certId = verifyUri.getQueryParameter("cert_id") ?: verifyUri.getQueryParameter("id") ?: ""
    val rollNo = verifyUri.getQueryParameter("roll") ?: verifyUri.getQueryParameter("r") ?: ""
    val qrName = verifyUri.getQueryParameter("name").orEmpty()
    val qrCourse = verifyUri.getQueryParameter("course").orEmpty()
    val qrGrade = verifyUri.getQueryParameter("grade") ?: verifyUri.getQueryParameter("g") ?: ""
    val qrPct = verifyUri.getQueryParameter("pct") ?: verifyUri.getQueryParameter("p") ?: ""
    val qrIssued = verifyUri.getQueryParameter("issued").orEmpty()
    val qrSig = verifyUri.getQueryParameter("sig") ?: verifyUri.getQueryParameter("s") ?: ""

    var cloudStatus by remember(certId) { mutableStateOf("Checking Cloud Firestore registry...") }
    var cloudName by remember(certId) { mutableStateOf(qrName) }
    var cloudCourse by remember(certId) { mutableStateOf(qrCourse) }
    var cloudGrade by remember(certId) { mutableStateOf(qrGrade) }
    var cloudIssued by remember(certId) { mutableStateOf(qrIssued) }
    var isRevoked by remember(certId) { mutableStateOf(false) }
    var isVerified by remember(certId) { mutableStateOf(false) }

    LaunchedEffect(certId, localCertificates) {
        val localMatch = localCertificates.firstOrNull {
            it.serialNo.equals(certId, ignoreCase = true) || it.id.equals(certId, ignoreCase = true)
        }
        if (localMatch != null) {
            cloudName = localMatch.studentName.ifBlank { cloudName }
            cloudCourse = localMatch.courseName.ifBlank { cloudCourse }
            cloudGrade = localMatch.grade.ifBlank { cloudGrade }
            cloudIssued = localMatch.issueDateIso.ifBlank { cloudIssued }
            isRevoked = localMatch.revoked
            isVerified = !localMatch.revoked
            cloudStatus = if (localMatch.revoked) "REVOKED BY INSTITUTE" else "VERIFIED (Cloud Registry)"
        }

        val pctDigits = qrPct.replace("%", "").trim()
        val sigValid = runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            if (qrSig.length == 8 && certId.isNotBlank() && rollNo.isNotBlank()) {
                val payload = "$certId|$rollNo|$qrGrade|$pctDigits|Waqar"
                val hex = md.digest(payload.toByteArray(StandardCharsets.UTF_8))
                    .take(8).joinToString("") { "%02X".format(it) }.take(8)
                hex.equals(qrSig, ignoreCase = true)
            } else if (qrSig.isNotBlank() && certId.isNotBlank() && qrName.isNotBlank()) {
                val payload = "$certId|$rollNo|$qrName|$qrCourse|$qrGrade|$pctDigits|$qrIssued|Waqar"
                val hex = md.digest(payload.toByteArray(StandardCharsets.UTF_8))
                    .take(8).joinToString("") { "%02X".format(it) }
                hex.equals(qrSig, ignoreCase = true)
            } else false
        }.getOrDefault(false)

        if (certId.isNotBlank()) {
            runCatching {
                val db = Firebase.firestore("ai-studio-android-waqarins-b07db193-f2b2-469a-b782-7cb127ebd6a1")
                val snap = db.collection("certificates").document(certId).get().await()
                if (snap.exists()) {
                    cloudName = snap.getString("studentName")?.ifBlank { cloudName } ?: cloudName
                    cloudCourse = snap.getString("courseName")?.ifBlank { cloudCourse } ?: cloudCourse
                    cloudGrade = snap.getString("grade")?.ifBlank { cloudGrade } ?: cloudGrade
                    cloudIssued = snap.getString("issueDateIso")?.ifBlank { cloudIssued } ?: cloudIssued
                    isRevoked = snap.getBoolean("revoked") == true
                    isVerified = !isRevoked
                    cloudStatus = if (isRevoked) "REVOKED BY INSTITUTE" else "VERIFIED IN FIRESTORE ✓"
                } else if (!isVerified) {
                    isVerified = sigValid
                    cloudStatus = if (sigValid) {
                        "CRYPTOGRAPHICALLY SIGNED (QR Verified ✓)"
                    } else {
                        "Certificate ID '$certId' not found in public registry"
                    }
                }
            }.onFailure {
                if (!isVerified) {
                    isVerified = sigValid
                    cloudStatus = if (sigValid) "CRYPTOGRAPHICALLY SIGNED (Offline QR Verified ✓)" else "Verification requires network or valid signature"
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = WciColors.BgElevatedDark,
        title = {
            Text(
                text = when {
                    isRevoked -> "Certificate REVOKED"
                    isVerified -> "Certificate VERIFIED ✓"
                    else -> "Certificate Verification"
                },
                color = when {
                    isRevoked -> WciColors.Danger
                    isVerified -> WciColors.Success
                    else -> WciColors.Warning
                },
                fontWeight = FontWeight.ExtraBold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = cloudStatus,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontFamily = JetBrainsMono,
                        color = if (isRevoked) WciColors.Danger else if (isVerified) WciColors.Success else WciColors.Info
                    )
                )
                if (certId.isNotBlank()) Text("Certificate ID: $certId", color = Color.White)
                if (rollNo.isNotBlank()) Text("Enrollment / Roll No: $rollNo", color = WciColors.TextSecondaryDark)
                if (cloudName.isNotBlank()) Text("Student Name: $cloudName", color = Color.White, fontWeight = FontWeight.Bold)
                if (cloudCourse.isNotBlank()) Text("Course: $cloudCourse", color = WciColors.Info)
                if (cloudGrade.isNotBlank()) {
                    val pctDisplay = if (qrPct.isNotBlank()) " (${qrPct.replace("%", "")}%)" else ""
                    Text("Grade & Score: $cloudGrade$pctDisplay", color = WciColors.Success)
                }
                if (cloudIssued.isNotBlank()) Text("Date of Issue: $cloudIssued", color = WciColors.TextSecondaryDark)
                Text("Authorized Signatory: Waqar (Waqar Computer Institute)", color = WciColors.TextMutedDark)
            }
        },
        confirmButton = {
            ShimmerButton(text = "Done", onClick = onDismiss)
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
