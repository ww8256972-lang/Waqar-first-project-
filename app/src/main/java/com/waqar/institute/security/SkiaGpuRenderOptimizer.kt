package com.waqar.institute.security

import android.app.Activity
import android.os.Build
import android.view.Surface
import android.view.WindowManager

/**
 * GPU-Accelerated Skia Graphics & 120Hz High-Refresh-Rate Render Pipeline Optimizer.
 *
 * Configures hardware-accelerated window flags, high-frame-rate display mode selection,
 * and low-latency frame pacing hints for Jetpack Compose lists and dashboards.
 */
object SkiaGpuRenderOptimizer {

    /**
     * Enables hardware acceleration on the host Activity window and negotiates the highest
     * supported refresh rate (90Hz / 120Hz / 144Hz) with the display compositor.
     */
    fun enableHighRefreshRateGpuPipeline(activity: Activity) {
        val window = activity.window ?: return

        // Enforce hardware-accelerated Skia/RenderThread drawing on the window surface
        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)

        // Select the highest refresh rate display mode matching the current resolution (Android M+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                @Suppress("DEPRECATION")
                val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    activity.display
                } else {
                    activity.windowManager.defaultDisplay
                } ?: return@runCatching

                val supportedModes = display.supportedModes
                val activeMode = display.mode
                val bestHighHzMode = supportedModes
                    .filter {
                        it.physicalWidth == activeMode.physicalWidth &&
                            it.physicalHeight == activeMode.physicalHeight
                    }
                    .maxByOrNull { it.refreshRate }

                if (bestHighHzMode != null && bestHighHzMode.refreshRate > 60f) {
                    val params = window.attributes
                    params.preferredDisplayModeId = bestHighHzMode.modeId
                    params.preferredRefreshRate = bestHighHzMode.refreshRate
                    window.attributes = params
                }
            }
        }

        // Request 120fps frame rate hint on the underlying Surface (Android R / API 30+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                window.decorView.post {
                    runCatching {
                        val surfaceControl = window.decorView.rootSurfaceControl
                        if (surfaceControl != null) {
                            // Frame rate preference is handled by Window attributes + RenderThread
                            window.attributes = window.attributes.apply {
                                preferredRefreshRate = 120f
                            }
                        }
                    }
                }
            }
        }
    }
}
