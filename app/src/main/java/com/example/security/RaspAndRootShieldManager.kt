package com.example.security

import android.content.Context
import com.aheaditec.talsec_security.security.api.SuspiciousAppInfo
import com.aheaditec.talsec_security.security.api.Talsec
import com.aheaditec.talsec_security.security.api.TalsecConfig
import com.aheaditec.talsec_security.security.api.ThreatListener
import com.example.BuildConfig
import com.scottyab.rootbeer.RootBeer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.lsposed.lsparanoid.Obfuscate

/**
 * Unified Runtime Application Self-Protection (RASP) & Root Shield (`RaspAndRootShieldManager`).
 *
 * Combines:
 * 1. Synchronous `RootBeer` Native C + Java `su`, `busybox`, and RW `/system` mount detection.
 * 2. Continuous `Talsec freeRASP` (`TalsecSecurity-Community`) runtime monitoring for KernelSU,
 *    Magisk, Zygisk, Frida/LSPosed hooks, MT Manager repackaging, overlay malware, screenshots,
 *    and screen recording.
 */
@Obfuscate
object RaspAndRootShieldManager : ThreatListener.ThreatDetected {
    private val _securityViolation = MutableStateFlow<String?>(null)
    val securityViolation: StateFlow<String?> = _securityViolation

    fun initializeShield(context: Context, expectedCertBase64Hash: String) {
        val appContext = context.applicationContext

        // 1. Synchronous RootBeer Native C + BusyBox + RW /system check
        runCatching {
            val rootBeer = RootBeer(appContext)
            if (rootBeer.isRootedWithBusyBoxCheck || rootBeer.detectRootManagementApps()) {
                _securityViolation.value = "ROOT_OR_BUSYBOX_DETECTED"
            }
        }

        // 2. Talsec freeRASP continuous runtime shield
        runCatching {
            val config = TalsecConfig.Builder(
                appContext.packageName,
                arrayOf(expectedCertBase64Hash)
            )
                .watcherMail("samdkhan8256972@gmail.com")
                .supportedAlternativeStores(arrayOf("com.android.vending"))
                .prod(!BuildConfig.DEBUG)
                .build()

            ThreatListener(this).registerListener(appContext)
            Talsec.start(appContext, config)
        }
    }

    override fun onRootDetected() {
        _securityViolation.value = "RASP_ROOT_KERNELSU_DETECTED"
    }

    override fun onDebuggerDetected() {
        _securityViolation.value = "RASP_DEBUGGER_ATTACHED"
    }

    override fun onEmulatorDetected() {
        _securityViolation.value = "RASP_EMULATOR_BLOCKED"
    }

    override fun onTamperDetected() {
        _securityViolation.value = "RASP_APK_REPACKAGED_MT_MANAGER"
    }

    override fun onUntrustedInstallationSourceDetected() {
        _securityViolation.value = "RASP_UNTRUSTED_INSTALLER"
    }

    override fun onHookDetected() {
        _securityViolation.value = "RASP_FRIDA_LSPOSED_HOOK_DETECTED"
    }

    override fun onDeviceBindingDetected() {
        _securityViolation.value = "RASP_DEVICE_CLONE_DETECTED"
    }

    override fun onObfuscationIssuesDetected() {
        _securityViolation.value = "RASP_DEOBFUSCATION_TAMPER"
    }

    override fun onMalwareDetected(apps: MutableList<SuspiciousAppInfo>?) {
        _securityViolation.value = "RASP_OVERLAY_MALWARE"
    }

    override fun onScreenshotDetected() {
        _securityViolation.value = "RASP_SCREENSHOT_ATTEMPT"
    }

    override fun onScreenRecordingDetected() {
        _securityViolation.value = "RASP_SCREEN_RECORDING_ATTEMPT"
    }

    fun onMultiInstanceDetected() {
        _securityViolation.value = "RASP_DUAL_SPACE_CLONE_DETECTED"
    }
}
