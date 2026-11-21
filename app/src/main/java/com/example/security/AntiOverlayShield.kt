package com.example.security

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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
    val activeAccessibilityServiceIds: List<String>,
    val suspiciousAccessibilityServiceIds: List<String> = activeAccessibilityServiceIds
) {
    val isSafeForProctoredExam: Boolean
        get() = !isExternalOrVirtualDisplayConnected && suspiciousAccessibilityServiceIds.isEmpty()
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

        // Flag third-party / automation / screen-scraping accessibility services active during CBT exams
        val suspiciousServices = enabledServices.filter { info ->
            val idLower = info.id?.lowercase().orEmpty()
            val canReadWindowContent =
                (info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT) != 0
            val isTalkBack = idLower.contains("com.google.android.marvin.talkback") ||
                idLower.contains("com.samsung.android.accessibility.talkback")
            canReadWindowContent || (!isTalkBack && idLower.isNotBlank())
        }.mapNotNull { it.id?.takeIf { id -> id.isNotBlank() } }

        return OverlaySecurityPosture(
            isOverlayShieldApplied = true,
            isExternalOrVirtualDisplayConnected = nonDefaultDisplays.isNotEmpty(),
            externalDisplayNames = nonDefaultDisplays.map { "${it.name} (ID:${it.displayId})" },
            hasScreenReaderOrScraperActive = serviceNames.isNotEmpty(),
            activeAccessibilityServiceIds = serviceNames,
            suspiciousAccessibilityServiceIds = suspiciousServices
        )
    }

    /**
     * Registers a live `DisplayManager.DisplayListener` during active CBT exams so newly attached
     * virtual/cast/secondary displays (AnyDesk, Miracast, HDMI capture) are caught immediately.
     * Returns an unregister lambda suitable for `DisposableEffect.onDispose`.
     */
    fun observeSecondaryDisplays(
        context: Context,
        onSecondaryDisplayDetected: (List<String>) -> Unit
    ): () -> Unit {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            ?: return {}
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) {
                    val names = dm.displays
                        .filter { it.displayId != Display.DEFAULT_DISPLAY }
                        .map { "${it.name} (ID:${it.displayId})" }
                    if (names.isNotEmpty()) {
                        onSecondaryDisplayDetected(names)
                    }
                }
            }

            override fun onDisplayRemoved(displayId: Int) = Unit

            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) {
                    val names = dm.displays
                        .filter { it.displayId != Display.DEFAULT_DISPLAY }
                        .map { "${it.name} (ID:${it.displayId})" }
                    if (names.isNotEmpty()) {
                        onSecondaryDisplayDetected(names)
                    }
                }
            }
        }
        runCatching {
            dm.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        }
        return {
            runCatching { dm.unregisterDisplayListener(listener) }
        }
    }
}
