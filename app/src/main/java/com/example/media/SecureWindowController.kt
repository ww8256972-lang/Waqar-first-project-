package com.example.media

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.lsposed.lsparanoid.Obfuscate

/**
 * Smart Screenshot Shield & Context-Aware Window Controller (`SecureWindowController.kt`).
 *
 * Dynamically applies `WindowManager.LayoutParams.FLAG_SECURE` and Android 12+
 * `window.setHideOverlayWindows(true)` ONLY on sensitive screens:
 * - Online Examination Hall & Question Paper (`LOCKED_SENSITIVE`)
 * - Dynamic QR Attendance Generator (`LOCKED_SENSITIVE`)
 * - Security & Cloud Credentials Vault (`LOCKED_SENSITIVE`)
 *
 * And explicitly CLEARS `FLAG_SECURE` (`UNLOCKED_STUDY_FRIENDLY`) on educational screens:
 * - Study Materials, Notes & Assignments (`M10_MATERIALS`)
 * - Live Classes & Recorded Video Lectures (`M17_LIVE_CLASSES`)
 * - Student Certificates & Marksheets (`M11_CERTIFICATES`)
 * so students can take study screenshots and download notes freely.
 */
enum class ScreenCapturePolicy {
    LOCKED_SENSITIVE,
    UNLOCKED_STUDY_FRIENDLY
}

@Obfuscate
object SecureWindowController {

    private val _policyFlow = MutableStateFlow(ScreenCapturePolicy.UNLOCKED_STUDY_FRIENDLY)
    val policyFlow: StateFlow<ScreenCapturePolicy> = _policyFlow.asStateFlow()

    @Volatile
    private var portalEnforcedLock: Boolean = false

    fun setPortalEnforcedLock(locked: Boolean) {
        portalEnforcedLock = locked
    }

    fun applyPolicy(activity: Activity?, policy: ScreenCapturePolicy) {
        val lock = portalEnforcedLock || (policy == ScreenCapturePolicy.LOCKED_SENSITIVE)
        _policyFlow.value = if (lock) ScreenCapturePolicy.LOCKED_SENSITIVE else ScreenCapturePolicy.UNLOCKED_STUDY_FRIENDLY
        val window = activity?.window ?: return
        if (lock) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { window.setHideOverlayWindows(lock) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { activity.setRecentsScreenshotEnabled(!lock) }
        }
    }

    /**
     * Strictly scoped confidential window lock: honors portal-level FLAG_SECURE or explicit opt-in.
     */
    fun applyStrictConfidentialLock(
        activity: Activity?,
        enabled: Boolean,
        userExplicitOptIn: Boolean = false
    ) {
        val window = activity?.window ?: return
        val lock = portalEnforcedLock || (enabled && userExplicitOptIn)
        if (lock) {
            _policyFlow.value = ScreenCapturePolicy.LOCKED_SENSITIVE
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            _policyFlow.value = ScreenCapturePolicy.UNLOCKED_STUDY_FRIENDLY
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    fun tailrecFindActivity(context: Context): Activity? = when (context) {
        is Activity -> context
        is ContextWrapper -> tailrecFindActivity(context.baseContext)
        else -> null
    }
}

@Composable
fun BindScreenCapturePolicy(policy: ScreenCapturePolicy) {
    val context = LocalContext.current
    DisposableEffect(policy) {
        val activity = SecureWindowController.tailrecFindActivity(context)
        SecureWindowController.applyPolicy(activity, policy)
        onDispose {
            // Restore study-friendly mode only if portal-level lock is not active (e.g. Admin portal)
            if (policy == ScreenCapturePolicy.LOCKED_SENSITIVE) {
                SecureWindowController.applyPolicy(activity, ScreenCapturePolicy.UNLOCKED_STUDY_FRIENDLY)
            }
        }
    }
}
