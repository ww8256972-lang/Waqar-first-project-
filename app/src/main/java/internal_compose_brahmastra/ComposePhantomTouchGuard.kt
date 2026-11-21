package internal_compose_brahmastra

import android.view.MotionEvent
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter

/**
 * 2. `internal_compose_brahmastra/ComposePhantomTouchGuard.kt`
 * Jetpack Compose `Modifier.ultraSecureTouchGuard()` that inspects raw `MotionEvent` flags
 * (`FLAG_WINDOW_IS_OBSCURED` and `FLAG_WINDOW_IS_PARTIALLY_OBSCURED`) to block floating
 * overlay apps (floating AI/screen-share overlays) and validates elliptical finger touch geometry.
 */
object ComposePhantomTouchGuard {

    data class TouchSecurityAudit(
        val allowed: Boolean,
        val obscuredByOverlay: Boolean,
        val syntheticMacroSuspected: Boolean,
        val reason: String
    )

    fun evaluateMotionEventFlagsAndGeometry(
        motionFlags: Int,
        touchMajorPx: Float = 18f,
        touchMinorPx: Float = 14f,
        pressure: Float = 0.45f
    ): TouchSecurityAudit {
        val isObscured = (motionFlags and MotionEvent.FLAG_WINDOW_IS_OBSCURED) != 0 ||
            (motionFlags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0
        if (isObscured) {
            return TouchSecurityAudit(
                allowed = false,
                obscuredByOverlay = true,
                syntheticMacroSuspected = false,
                reason = "BLOCKED_OVERLAY_TAPJACKING"
            )
        }
        val zeroContactArea = touchMajorPx <= 0f && touchMinorPx <= 0f && pressure <= 0f
        if (zeroContactArea) {
            return TouchSecurityAudit(
                allowed = false,
                obscuredByOverlay = false,
                syntheticMacroSuspected = true,
                reason = "BLOCKED_ZERO_AREA_SYNTHETIC_CLICK"
            )
        }
        return TouchSecurityAudit(
            allowed = true,
            obscuredByOverlay = false,
            syntheticMacroSuspected = false,
            reason = "GENUINE_TOUCH_VERIFIED"
        )
    }

    fun evaluateLiveMotionEvent(event: MotionEvent): TouchSecurityAudit {
        val isFingerDown = event.actionMasked == MotionEvent.ACTION_DOWN &&
            event.pointerCount > 0 &&
            event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
        return evaluateMotionEventFlagsAndGeometry(
            motionFlags = event.flags,
            touchMajorPx = if (isFingerDown) event.touchMajor else 18f,
            touchMinorPx = if (isFingerDown) event.touchMinor else 14f,
            pressure = if (isFingerDown) event.pressure else 0.45f
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.ultraSecureTouchGuard(
    onBlockedOverlayOrMacro: ((String) -> Unit)? = null
): Modifier = this.pointerInteropFilter { event ->
    val verdict = ComposePhantomTouchGuard.evaluateLiveMotionEvent(event)
    if (!verdict.allowed) {
        onBlockedOverlayOrMacro?.invoke(verdict.reason)
        true // Consume and block the obscured/synthetic touch event
    } else {
        false // Allow normal Compose propagation
    }
}
