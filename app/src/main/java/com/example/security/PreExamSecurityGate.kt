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
    val blockingReasons: List<String>
) {
    val isClearedToStartExam: Boolean
        get() = !isRootedOrHooked && !isVpnActive && !isScreenMirroringActive
}

object PreExamSecurityGate {

    /**
     * Scrubs the system clipboard and runs the 6-point environment audit.
     */
    fun sanitizeAndAuditExamEnvironment(context: Context): PreExamSanitizationReport {
        val clipboardScrubbed = scrubSystemClipboard(context)
        val posture = EnterpriseRaspGuard.auditDeviceSecurity(context)
        val adbEnabled = isUsbDebuggingActive(context)
        val vpnActive = isVpnTunnelActive(context)
        val btDevices = detectConnectedBluetoothAudioOutputs(context)
        val overlayPosture = AntiOverlayShield.inspectDisplayAndOverlayPosture(context)

        val reasons = mutableListOf<String>()
        if (posture.isCompromised) {
            reasons.add("Root / Magisk / Su binary detected on device.")
        }
        if (vpnActive) {
            reasons.add("Active VPN / Proxy tunnel detected. Disable VPN before starting the exam.")
        }
        if (overlayPosture.isExternalOrVirtualDisplayConnected) {
            reasons.add("Screen Mirroring / External Display detected (${overlayPosture.externalDisplayNames.joinToString()}).")
        }
        if (btDevices.isNotEmpty()) {
            reasons.add("Bluetooth audio earpiece detected (${btDevices.joinToString()}). Disconnect Bluetooth audio during exam.")
        }

        return PreExamSanitizationReport(
            isRootedOrHooked = posture.isCompromised,
            isUsbDebuggingEnabled = adbEnabled,
            isVpnActive = vpnActive,
            isBluetoothAudioConnected = btDevices.isNotEmpty(),
            connectedBluetoothAudioDevices = btDevices,
            isScreenMirroringActive = overlayPosture.isExternalOrVirtualDisplayConnected,
            isClipboardScrubbed = clipboardScrubbed,
            blockingReasons = reasons
        )
    }

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
