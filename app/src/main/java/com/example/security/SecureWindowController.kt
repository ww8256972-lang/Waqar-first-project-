package com.example.security

import android.app.Activity
import com.example.media.ScreenCapturePolicy as MediaPolicy
import com.example.media.SecureWindowController as MediaSecureWindowController
import org.lsposed.lsparanoid.Obfuscate

/**
 * Forwarding delegate to com.example.media.SecureWindowController to ensure unified window security.
 */
@Obfuscate
object SecureWindowController {
    enum class ScreenCapturePolicy { LOCKED_SENSITIVE, UNLOCKED_NORMAL }

    fun applyPolicy(
        activity: Activity,
        policy: ScreenCapturePolicy = ScreenCapturePolicy.UNLOCKED_NORMAL
    ) {
        val target = when (policy) {
            ScreenCapturePolicy.LOCKED_SENSITIVE -> MediaPolicy.LOCKED_SENSITIVE
            ScreenCapturePolicy.UNLOCKED_NORMAL -> MediaPolicy.UNLOCKED_STUDY_FRIENDLY
        }
        MediaSecureWindowController.applyPolicy(activity, target)
    }
}
