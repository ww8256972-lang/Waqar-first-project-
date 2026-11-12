package com.example

import android.app.Activity
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.security.BaselineProfileOptimization
import com.example.security.EnterpriseRaspGuard
import com.example.security.MemoryScrubberLifecycleObserver
import com.example.security.RaspAndRootShieldManager
import com.example.security.SecureWindowController
import com.example.security.configureAppCheck
import com.example.ui.auth.NeonWaveLoginScreen
import com.example.ui.auth.TotpMfaChallengeDialog
import com.example.ui.designsystem.WciColors
import com.example.ui.portal.PortalShellScreen
import com.example.ui.theme.WaqarInstituteTheme
import com.example.viewmodel.InstituteViewModel
import com.waqar.institute.security.AntiHookManager
import com.waqar.institute.security.AppSelfHealingManager
import com.waqar.institute.security.LifecyclePrivacyBlurContainer
import com.waqar.institute.security.NativeSecurityBridge
import com.waqar.institute.security.RecentAppsBlurController
import com.waqar.institute.security.RecaptchaInvisibleManager
import com.waqar.institute.security.SkiaGpuRenderOptimizer
import com.waqar.institute.security.TeeSecurityManager
import com.waqar.institute.security.VirtualSpaceShield
import com.waqar.institute.utils.GlobalExceptionHandler
import com.waqar.institute.workers.InstituteBackgroundSyncWorker

class MainActivity : ComponentActivity() {
    private var screenCaptureCallback: Any? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // MANDATORY STEP 1: Configure Firebase App Check with launch intent before any UI or Firebase calls
        configureAppCheck(this, intent)

        // Enterprise Security Rule 1, 4 & 6: RASP Startup Integrity, Talsec freeRASP + RootBeer, Baseline Profile AOT & Memory Scrubber
        RaspAndRootShieldManager.initializeShield(
            context = applicationContext,
            expectedCertBase64Hash = "KpLcLKQN96jtvmfA493NKIoPfD5beiLgEzostnG8EdU="
        )
        EnterpriseRaspGuard.enforceStartupIntegrity(this)
        BaselineProfileOptimization.installAsync(this)
        lifecycle.addObserver(MemoryScrubberLifecycleObserver(this))

        // Defense-Grade Native C++20 APK Signature/DEX Integrity, ptrace Watchdog, TrustZone TEE & 120Hz Skia GPU Pipeline
        NativeSecurityBridge.initializeDefenseGradeSecurity(this)
        AppSelfHealingManager.initializeSelfHealingDaemon(this)
        AntiHookManager.runComprehensiveHookScan()
        VirtualSpaceShield.evaluateEnvironment(this)
        TeeSecurityManager.inspectHardwareKeyAttestation(this)
        SkiaGpuRenderOptimizer.enableHighRefreshRateGpuPipeline(this)
        GlobalExceptionHandler.install()
        InstituteBackgroundSyncWorker.schedulePeriodicEnterpriseSync(this)

        // Android 14+ (API 34+) Hardware Screenshot Attempt Detection Callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val callback = Activity.ScreenCaptureCallback {
                SecureWindowController.applyPolicy(
                    this,
                    SecureWindowController.ScreenCapturePolicy.LOCKED_SENSITIVE
                )
            }
            screenCaptureCallback = callback
            runCatching { registerScreenCaptureCallback(mainExecutor, callback) }
        }

        enableEdgeToEdge()
        setContent {
            WaqarInstituteApp()
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
        RecentAppsBlurController.onWindowFocusChanged(this, hasFocus)
    }
}

@Composable
fun WaqarInstituteApp() {
    val context = LocalContext.current
    val viewModel: InstituteViewModel = viewModel(factory = InstituteViewModel.Factory(context))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val rolePalette = WciColors.paletteForRole(state.activePortalRole)
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    LaunchedEffect(Unit) {
        viewModel.refreshRaspSecurityAudit(context)
        (context.applicationContext as? android.app.Application)?.let { app ->
            RecaptchaInvisibleManager.getOrInitClient(app)
        }
    }

    WaqarInstituteTheme(
        darkTheme = state.preferences.darkTheme,
        rolePalette = rolePalette,
        uiTier = state.effectiveUiTier,
        languageCode = state.preferences.languageCode
    ) {
        LifecyclePrivacyBlurContainer(enabled = state.preferences.flagSecureEnabled) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = WciColors.BgPrimaryDark
            ) {
                if (state.currentUser == null && state.userProfile == null) {
                    NeonWaveLoginScreen(
                        selectedRole = state.activePortalRole,
                        languageCode = state.preferences.languageCode,
                        instituteName = state.preferences.instituteName,
                        instituteTagline = state.preferences.instituteTagline,
                        isAuthenticating = state.isAuthenticating,
                        externalStatusMessage = state.statusMessage,
                        onRoleSelected = { role -> viewModel.switchPortalRole(role) },
                        onLanguageToggle = {
                            val next = if (state.preferences.languageCode == "HI") "EN" else "HI"
                            viewModel.setLanguage(next)
                        },
                        onCredentialLogin = { actCtx, role, username, password ->
                            viewModel.authenticateWithPortalCredentials(actCtx, role, username, password)
                        },
                        onGoogleLogin = { actCtx, role, customUsername ->
                            viewModel.authenticateWithGoogleRole(actCtx, role, customUsername)
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

                val raspViolation by RaspAndRootShieldManager.securityViolation.collectAsStateWithLifecycle()
                if (raspViolation != null && !BuildConfig.DEBUG) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { (context as? Activity)?.finishAffinity() },
                        title = { androidx.compose.material3.Text("Security Policy Violation") },
                        text = { androidx.compose.material3.Text("Application integrity breach detected ($raspViolation). For security, access is blocked.") },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = { (context as? Activity)?.finishAffinity() }) {
                                androidx.compose.material3.Text("Exit Application")
                            }
                        }
                    )
                }
            }
        }
    }
}
