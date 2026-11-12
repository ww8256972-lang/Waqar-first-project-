package com.example.security

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.View
import android.view.accessibility.AccessibilityManager

/**
 * Play-Policy-Compliant Anti-Overlay, Screen-Cast & Accessibility Interceptor (`AntiOverlayShield.kt`).
 *
 * Enforces:
 * 1. `window.setHideOverlayWindows(true)` (Android 12+ / API 31+) & `rootView.filterTouchesWhenObscured = true`
 *    to block Tapjacking, floating calculators, and screen-overlay attacks during exams and fee payments.
 * 2. `DisplayManager` Virtual / Presentation / Wireless Cast display detection (`detectActiveScreenMirroring`)
 *    to stop remote screen-sharing via AnyDesk, TeamViewer, Miracast, or HDMI capture cards.
 * 3. `AccessibilityManager` inspection (`detectSuspiciousAccessibilityScrapers`) to detect automated
 *    UI screen-scraping bots during proctored exams.
 */
data class OverlaySecurityPosture(
    val isOverlayShieldApplied: Boolean,
    val isExternalOrVirtualDisplayConnected: Boolean,
    val externalDisplayNames: List<String>,
    val hasScreenReaderOrScraperActive: Boolean,
    val activeAccessibilityServiceIds: List<String>
) {
    val isSafeForProctoredExam: Boolean
        get() = !isExternalOrVirtualDisplayConnected
}

object AntiOverlayShield {

    /**
     * Applies Android native tapjacking & non-system overlay protection to the current Activity window.
     */
    fun applyWindowOverlayShield(activity: Activity?, enabled: Boolean = true) {
        val window = activity?.window ?: return
        val decorView: View = window.decorView
        decorView.filterTouchesWhenObscured = enabled
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { window.setHideOverlayWindows(enabled) }
        }
    }

    /**
     * Inspects `DisplayManager` for secondary, virtual, wireless cast, or presentation displays
     * and checks `AccessibilityManager` for active screen-scraping services.
     */
    fun inspectDisplayAndOverlayPosture(context: Context): OverlaySecurityPosture {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val allDisplays = displayManager?.displays ?: emptyArray()
        val nonDefaultDisplays = allDisplays.filter { it.displayId != Display.DEFAULT_DISPLAY }

        val accessibilityManager =
            context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val enabledServices = accessibilityManager
            ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?: emptyList()

        val serviceNames = enabledServices.mapNotNull { info ->
            info.id?.takeIf { it.isNotBlank() }
        }

        return OverlaySecurityPosture(
            isOverlayShieldApplied = true,
            isExternalOrVirtualDisplayConnected = nonDefaultDisplays.isNotEmpty(),
            externalDisplayNames = nonDefaultDisplays.map { "${it.name} (ID:${it.displayId})" },
            hasScreenReaderOrScraperActive = serviceNames.isNotEmpty(),
            activeAccessibilityServiceIds = serviceNames
        )
    }
}
