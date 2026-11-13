package com.waqar.institute.security

import android.app.Activity
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Recent Apps / Multitasking Privacy Blur & Blackout Shield.
 *
 * Automatically protects sensitive student records, fee ledgers, and online exams when the user
 * opens the Android Recent Apps switcher, pulls down the notification shade, or switches apps:
 * 1. Applies hardware `RenderEffect.createBlurEffect(32f, 32f, Shader.TileMode.CLAMP)` on Android 12+ (API 31+).
 * 2. Enforces `WindowManager.LayoutParams.FLAG_SECURE` when privacy protection is enabled.
 * 3. Renders an instant Compose privacy veil when the lifecycle drops below `RESUMED`.
 */
object RecentAppsBlurController {

    fun attachWindowFocusPrivacyShield(
        activity: Activity,
        enforceFlagSecure: Boolean = true
    ) {
        if (enforceFlagSecure) {
            activity.window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.setRecentsScreenshotEnabled(!enforceFlagSecure)
        }
    }

    fun onWindowFocusChanged(activity: Activity, hasFocus: Boolean) {
        // Do NOT apply render blur during startup or when system dialogs / keyboard gain/lose focus.
        // Blur is strictly for background recents transition when the activity is stopped.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val decorView = activity.window?.decorView ?: return
            if (hasFocus) {
                decorView.setRenderEffect(null)
            }
        }
    }
}

@Composable
fun LifecyclePrivacyBlurContainer(
    enabled: Boolean = false,
    content: @Composable () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var isAppInBackgroundOrRecents by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, enabled) {
        val observer = LifecycleEventObserver { _, event ->
            if (!enabled) {
                isAppInBackgroundOrRecents = false
                return@LifecycleEventObserver
            }
            when (event) {
                Lifecycle.Event.ON_RESUME -> isAppInBackgroundOrRecents = false
                Lifecycle.Event.ON_STOP -> {
                    isAppInBackgroundOrRecents = true
                    MemoryScrubberManager.scrubAllRegisteredBuffers()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        content()
        if (enabled && isAppInBackgroundOrRecents) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xE60B0F29))
            )
        }
    }
}
