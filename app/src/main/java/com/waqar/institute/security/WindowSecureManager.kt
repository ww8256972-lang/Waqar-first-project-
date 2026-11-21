package com.waqar.institute.security

import android.app.Activity
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import com.example.data.model.PortalRole
import com.example.media.ScreenCapturePolicy
import com.example.media.SecureWindowController

/**
 * Portal-Specific Screenshot & Screen Recording Security Controller (`WindowSecureManager.kt`).
 *
 * Security Policy:
 * - **Teacher, Student, & Parent Portals** (`portalRole` in `["teacher", "student", "parent"]`):
 *   STRICTLY ENABLES `WindowManager.LayoutParams.FLAG_SECURE` (+ Android 12+ overlay suppression &
 *   Android 13+ recents screenshot disable) to completely block screenshots, screen recording, and
 *   app-switcher visual leaks.
 * - **Admin Portal** (`portalRole == "admin"`):
 *   CLEARS `FLAG_SECURE` by default so authorized Institute Administrators can capture and archive
 *   institute records, unless the Admin explicitly turns on `FLAG_SECURE` in Settings.
 */
object WindowSecureManager {

    private val protectedPortalRoles = setOf(
        PortalRole.TEACHER.key,
        PortalRole.STUDENT.key,
        PortalRole.PARENT.key
    )

    fun isPortalProtected(
        portalRole: String,
        isAuthenticated: Boolean = true,
        allowAdminSecureOverride: Boolean = false
    ): Boolean {
        if (!isAuthenticated) return false
        val normalized = portalRole.trim().lowercase()
        return when {
            normalized in protectedPortalRoles -> true
            normalized == PortalRole.ADMIN.key -> allowAdminSecureOverride
            else -> true
        }
    }

    fun applyPortalSecurity(
        activity: Activity?,
        portalRole: String,
        isAuthenticated: Boolean = true,
        allowAdminSecureOverride: Boolean = false
    ) {
        val shouldBlockCapture = isPortalProtected(
            portalRole = portalRole,
            isAuthenticated = isAuthenticated,
            allowAdminSecureOverride = allowAdminSecureOverride
        )

        SecureWindowController.setPortalEnforcedLock(shouldBlockCapture)

        val window = activity?.window ?: return
        if (shouldBlockCapture) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
            SecureWindowController.applyPolicy(activity, ScreenCapturePolicy.LOCKED_SENSITIVE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            SecureWindowController.applyPolicy(activity, ScreenCapturePolicy.UNLOCKED_STUDY_FRIENDLY)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { window.setHideOverlayWindows(shouldBlockCapture) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { activity.setRecentsScreenshotEnabled(!shouldBlockCapture) }
        }
    }
}

@Composable
fun BindPortalWindowSecurity(
    portalRole: String,
    isAuthenticated: Boolean,
    allowAdminSecureOverride: Boolean = false
) {
    val context = LocalContext.current
    val activity = SecureWindowController.tailrecFindActivity(context)

    SideEffect {
        WindowSecureManager.applyPortalSecurity(
            activity = activity,
            portalRole = portalRole,
            isAuthenticated = isAuthenticated,
            allowAdminSecureOverride = allowAdminSecureOverride
        )
    }

    DisposableEffect(activity, portalRole, isAuthenticated, allowAdminSecureOverride) {
        WindowSecureManager.applyPortalSecurity(
            activity = activity,
            portalRole = portalRole,
            isAuthenticated = isAuthenticated,
            allowAdminSecureOverride = allowAdminSecureOverride
        )
        onDispose {
            if (!isAuthenticated) {
                WindowSecureManager.applyPortalSecurity(
                    activity = activity,
                    portalRole = PortalRole.ADMIN.key,
                    isAuthenticated = false,
                    allowAdminSecureOverride = false
                )
            }
        }
    }
}
