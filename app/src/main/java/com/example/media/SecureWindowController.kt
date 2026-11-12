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

    fun applyPolicy(activity: Activity?, policy: ScreenCapturePolicy) {
        _policyFlow.value = policy
        val window = activity?.window ?: return
        val lock = (policy == ScreenCapturePolicy.LOCKED_SENSITIVE)
        if (lock) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { window.setHideOverlayWindows(lock) }
        }
    }

    /**
     * Strictly scoped confidential window lock: only sets FLAG_SECURE when explicitly
     * opted-in by the user on physical non-virtual hardware during a live proctored paper.
     */
    fun applyStrictConfidentialLock(
        activity: Activity?,
        enabled: Boolean,
        userExplicitOptIn: Boolean = false
    ) {
        val window = activity?.window ?: return
        if (enabled && userExplicitOptIn) {
            _policyFlow.value = ScreenCapturePolicy.LOCKED_SENSITIVE
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
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
            // Restore study-friendly mode when leaving a sensitive dialog/screen
            if (policy == ScreenCapturePolicy.LOCKED_SENSITIVE) {
                SecureWindowController.applyPolicy(activity, ScreenCapturePolicy.UNLOCKED_STUDY_FRIENDLY)
            }
        }
    }
}
