package com.waqar.institute.security

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

/**
 * [A1] Exam Kiosk Lock-Task + HDMI / USB-C / Miracast / Chromecast Screen-Cast Blocker.
 *
 * Engages Android Screen Pinning (`startLockTask()`) during proctored CBT exams and listens
 * for any non-default physical or virtual presentation display (`Display.DEFAULT_DISPLAY`),
 * immediately notifying the CBT player to lock/flag the session if mirroring is attempted.
 */
object ExamDisplayAndKioskShield {
    @Volatile
    private var displayListener: DisplayManager.DisplayListener? = null

    fun hasExternalOrVirtualDisplay(context: Context): Boolean {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return false
        return dm.displays.any { d ->
            d.displayId != Display.DEFAULT_DISPLAY && d.state != Display.STATE_OFF
        }
    }

    fun hasOtgOrExternalDeviceConnected(context: Context): Boolean {
        val otg = com.example.security.PreExamSecurityGate.detectConnectedUsbOtgOrExternalDevices(context)
        return otg.isNotEmpty()
    }

    fun engageExamKioskAndDisplayWatchdog(
        activity: Activity,
        onExternalDisplayDetected: (String) -> Unit
    ) {
        runCatching { activity.startLockTask() }
        val dm = activity.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        // Unregister any stale listener before registering a fresh one
        displayListener?.let { old -> runCatching { dm.unregisterDisplayListener(old) } }

        if (hasExternalOrVirtualDisplay(activity)) {
            onExternalDisplayDetected("External HDMI / USB-C / Wireless Cast display detected. Exam locked.")
        }
        if (hasOtgOrExternalDeviceConnected(activity)) {
            val otgDevices = com.example.security.PreExamSecurityGate.detectConnectedUsbOtgOrExternalDevices(activity)
            onExternalDisplayDetected("External OTG / USB Peripheral detected: ${otgDevices.joinToString()}. Exam locked.")
        }

        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) {
                    val name = dm.getDisplay(displayId)?.name ?: "External Display #$displayId"
                    onExternalDisplayDetected("Blocked screen cast / HDMI mirror: $name")
                }
            }

            override fun onDisplayRemoved(displayId: Int) = Unit

            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY && hasExternalOrVirtualDisplay(activity)) {
                    onExternalDisplayDetected("Active external presentation display detected during exam.")
                }
            }
        }
        displayListener = listener
        runCatching { dm.registerDisplayListener(listener, null) }
    }

    fun releaseExamKioskAndDisplayWatchdog(activity: Activity) {
        val dm = activity.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        displayListener?.let { listener ->
            runCatching { dm?.unregisterDisplayListener(listener) }
        }
        displayListener = null
        runCatching { activity.stopLockTask() }
    }
}
