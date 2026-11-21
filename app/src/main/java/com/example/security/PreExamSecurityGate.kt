package com.example.security

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings

/**
 * Pre-Exam Environment Sanitizer & Hardware Security Gate (`PreExamSecurityGate.kt`).
 *
 * Executes a 6-Point Military-Grade Pre-Exam Environment Audit before unlocking an online exam:
 * 1. Root / Magisk / Zygisk Binary & Native RASP Inspection (`SecurityConfig` + `NativeRaspBridge`).
 * 2. ADB / USB Debugging Inspection (`Settings.Global.ADB_ENABLED`).
 * 3. Active VPN / Proxy Tunnel Detection (`NetworkCapabilities.TRANSPORT_VPN`).
 * 4. System Clipboard Scrubber (`ClipboardManager.clearPrimaryClip()`) to wipe pre-copied cheat notes.
 * 5. External Bluetooth Headset / Hidden Earpiece Detection (`AudioManager.getDevices(GET_DEVICES_OUTPUTS)`).
 * 6. Secondary Display / Screen-Cast / Overlay Audit (`AntiOverlayShield`).
 */
data class PreExamSanitizationReport(
    val isRootedOrHooked: Boolean,
    val isUsbDebuggingEnabled: Boolean,
    val isVpnActive: Boolean,
    val isBluetoothAudioConnected: Boolean,
    val connectedBluetoothAudioDevices: List<String>,
    val isScreenMirroringActive: Boolean,
    val isClipboardScrubbed: Boolean,
    val blockingReasons: List<String>,
    val isDeveloperOptionsEnabled: Boolean = false,
    val isEmulatorDetected: Boolean = false,
    val hasSuspiciousAccessibilityServices: Boolean = false,
    val activeAccessibilityServices: List<String> = emptyList(),
    val isOtgOrExternalDeviceConnected: Boolean = false,
    val connectedExternalDevices: List<String> = emptyList()
) {
    val isClearedToStartExam: Boolean
        get() = !isRootedOrHooked &&
            !isUsbDebuggingEnabled &&
            !isDeveloperOptionsEnabled &&
            !isEmulatorDetected &&
            !isVpnActive &&
            !isScreenMirroringActive &&
            !hasSuspiciousAccessibilityServices &&
            !isOtgOrExternalDeviceConnected
}

object PreExamSecurityGate {

    /**
     * Scrubs the system clipboard and runs the full pre-exam environment audit.
     */
    fun sanitizeAndAuditExamEnvironment(context: Context): PreExamSanitizationReport {
        val clipboardScrubbed = scrubSystemClipboard(context)
        val posture = EnterpriseRaspGuard.auditDeviceSecurity(context)
        val adbEnabled = isUsbDebuggingActive(context)
        val devOptionsEnabled = isDeveloperOptionsActive(context)
        val emulatorDetected = posture.isEmulatorEnvironment
        val vpnActive = isVpnTunnelActive(context)
        val btDevices = detectConnectedBluetoothAudioOutputs(context)
        val overlayPosture = AntiOverlayShield.inspectDisplayAndOverlayPosture(context)
        val externalDevices = detectConnectedUsbOtgOrExternalDevices(context)

        val reasons = mutableListOf<String>()
        if (posture.isCompromised) {
            reasons.add("Root / Magisk / Frida / Xposed hook detected on device.")
        }
        if (emulatorDetected) {
            reasons.add("PC Emulator / Hypervisor environment detected. Physical Android phone required for exams.")
        }
        if (adbEnabled) {
            reasons.add("USB Debugging (ADB) is enabled. Turn off USB Debugging in Developer Options to start the exam.")
        }
        if (devOptionsEnabled) {
            reasons.add("Developer Options are enabled. Disable Developer Options in Settings to start the exam.")
        }
        if (vpnActive) {
            reasons.add("Active VPN / Proxy tunnel detected. Disable VPN before starting the exam.")
        }
        if (overlayPosture.isExternalOrVirtualDisplayConnected) {
            reasons.add("Screen Mirroring / External Display detected (${overlayPosture.externalDisplayNames.joinToString()}).")
        }
        if (overlayPosture.suspiciousAccessibilityServiceIds.isNotEmpty()) {
            reasons.add(
                "Unauthorized Accessibility / Screen-Scraper service active (${overlayPosture.suspiciousAccessibilityServiceIds.joinToString()}). Disable before exam."
            )
        }
        if (btDevices.isNotEmpty()) {
            reasons.add("Bluetooth audio earpiece detected (${btDevices.joinToString()}). Disconnect Bluetooth audio during exam.")
        }
        if (externalDevices.isNotEmpty()) {
            reasons.add("OTG / External Device connection detected (${externalDevices.joinToString()}). Disconnect all external USB accessories, keyboards, mice, or OTG peripherals before taking the exam.")
        }

        return PreExamSanitizationReport(
            isRootedOrHooked = posture.isCompromised,
            isUsbDebuggingEnabled = adbEnabled,
            isVpnActive = vpnActive,
            isBluetoothAudioConnected = btDevices.isNotEmpty(),
            connectedBluetoothAudioDevices = btDevices,
            isScreenMirroringActive = overlayPosture.isExternalOrVirtualDisplayConnected,
            isClipboardScrubbed = clipboardScrubbed,
            blockingReasons = reasons,
            isDeveloperOptionsEnabled = devOptionsEnabled,
            isEmulatorDetected = emulatorDetected,
            hasSuspiciousAccessibilityServices = overlayPosture.suspiciousAccessibilityServiceIds.isNotEmpty(),
            activeAccessibilityServices = overlayPosture.suspiciousAccessibilityServiceIds,
            isOtgOrExternalDeviceConnected = externalDevices.isNotEmpty(),
            connectedExternalDevices = externalDevices
        )
    }

    /**
     * Detects any connected USB OTG accessories, external keyboards, mice, or hardware peripherals.
     */
    fun detectConnectedUsbOtgOrExternalDevices(context: Context): List<String> = runCatching {
        val detected = mutableListOf<String>()

        // 1. Check USB Manager for attached OTG peripherals / capture cards
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? android.hardware.usb.UsbManager
        usbManager?.deviceList?.values?.forEach { dev ->
            val desc = dev.productName?.takeIf { it.isNotBlank() }
                ?: "USB Peripheral (Vendor:${dev.vendorId}, Product:${dev.productId})"
            detected.add("USB OTG: $desc")
        }

        // 2. Check InputManager / InputDevice for physical external input hardware
        val deviceIds = android.view.InputDevice.getDeviceIds()
        for (id in deviceIds) {
            val dev = android.view.InputDevice.getDevice(id) ?: continue
            val isExt = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                dev.isExternal
            } else {
                !dev.isVirtual
            }
            if (isExt && !dev.isVirtual) {
                val sources = dev.sources
                val isExternalInput = (sources and android.view.InputDevice.SOURCE_KEYBOARD != 0) ||
                    (sources and android.view.InputDevice.SOURCE_MOUSE != 0) ||
                    (sources and android.view.InputDevice.SOURCE_TOUCHPAD != 0) ||
                    (sources and android.view.InputDevice.SOURCE_GAMEPAD != 0)
                if (isExternalInput) {
                    detected.add("External Input: ${dev.name}")
                }
            }
        }

        detected.distinct()
    }.getOrDefault(emptyList())

    /**
     * Wipes any pre-copied text or formulas from the Android system clipboard before the exam opens.
     */
    fun scrubSystemClipboard(context: Context): Boolean = runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip()
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
            true
        } else false
    }.getOrDefault(false)

    private fun isUsbDebuggingActive(context: Context): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) != 0
    }.getOrDefault(false)

    private fun isDeveloperOptionsActive(context: Context): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0
    }.getOrDefault(false)

    private fun isVpnTunnelActive(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val activeNet = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNet) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }.getOrDefault(false)

    private fun detectConnectedBluetoothAudioOutputs(context: Context): List<String> = runCatching {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return emptyList()
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        outputs.filter { dev ->
            dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }.map { dev ->
            dev.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth Audio Headset"
        }.distinct()
    }.getOrDefault(emptyList())
}
