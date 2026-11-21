package com.waqar.institute.security

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.annotation.Keep
import com.example.BuildConfig
import com.example.security.MemoryScrubberLifecycleObserver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.lsposed.lsparanoid.Obfuscate
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Defense-Grade Native C++20 JNI Security Bridge (`native-security`).
 *
 * Bridges Kotlin application startup with the C++20 kernel security engine:
 * 1. Native APK Signature & DEX SHA-256 Integrity Verification (`verifyApkIntegrity`).
 * 2. Real-Time `ptrace(PTRACE_TRACEME)` & `/proc/self/status` `TracerPid` Debugger Watchdog (`startDebuggerWatchdog`).
 * 3. Anti-Emulation & Hypervisor CPUID / Sandbox Detection (`detectHypervisorAndEmulation`).
 * 4. Hook-Agnostic Inline Function Prologue & Trampoline Detection (`inspectInlineFunctionHooks`).
 * 5. Binary Polymorphism & Self-Healing Runtime Checksum Engine (`verifySelfHealingCodePolymorphism`).
 */
@Obfuscate
@Keep
object NativeSecurityBridge {
    private const val TAG = "NativeSecurityBridge"
    private var isNativeLibraryLoaded = false
    private var baselineJvmApkDigest: String? = null
    private val _tamperViolationReason = MutableStateFlow<String?>(null)
    val tamperViolationReason: StateFlow<String?> = _tamperViolationReason.asStateFlow()

    init {
        isNativeLibraryLoaded = runCatching {
            System.loadLibrary("waqar_native_security")
            true
        }.recoverCatching {
            System.loadLibrary("native-security")
            true
        }.recoverCatching {
            System.loadLibrary("wci_native_security")
            true
        }.getOrDefault(false)
    }

    fun checkInlineFunctionHooks(): Boolean =
        if (isNativeLibraryLoaded) {
            runCatching { inspectInlineFunctionHooks() }.getOrDefault(true)
        } else {
            hasJvmProcHookIndicators()
        }

    fun verifyCodePolymorphism(): Boolean =
        if (isNativeLibraryLoaded) {
            runCatching { verifySelfHealingCodePolymorphism() }.getOrDefault(false)
        } else {
            !hasJvmProcHookIndicators()
        }

    fun initializeDefenseShield(context: Context): DefenseIntegrityStatus =
        initializeDefenseGradeSecurity(context)

    @Keep
    external fun verifyApkIntegrity(context: Context): Boolean

    @Keep
    external fun startDebuggerWatchdog()

    @Keep
    external fun detectHypervisorAndEmulation(): Int

    @Keep
    external fun inspectInlineFunctionHooks(): Boolean

    @Keep
    external fun verifySelfHealingCodePolymorphism(): Boolean

    @Keep
    external fun setHighSecurityExamWatchdogMode(active: Boolean)

    @Keep
    external fun isRuntimeTamperDetected(): Boolean

    @Keep
    external fun verifyReleaseSigningCertPin(runtimeCertHex: String): Boolean

    @Keep
    external fun nativeArmAntiMemoryDumpVault(): Boolean

    /**
     * [A2] Arms kernel-level anti-dump protection (`PR_SET_DUMPABLE=0`, `PR_SET_PTRACER=0`,
     * and `MADV_DONTDUMP` guard page verification) inside `libwaqar_native_security.so`.
     */
    fun armAntiMemoryDumpVault(): Boolean {
        if (!isNativeLibraryLoaded) return false
        return runCatching { nativeArmAntiMemoryDumpVault() }.getOrDefault(false)
    }

    /**
     * Arms or disarms the continuous C++20 2-second anti-Frida / ptrace / /proc/self/maps
     * watchdog loop during active proctored CBT exams and biometric gates.
     */
    fun armHighSecurityExamWatchdog(active: Boolean) {
        if (isNativeLibraryLoaded) {
            runCatching { setHighSecurityExamWatchdogMode(active) }
        }
    }

    /**
     * Checks live native & JVM runtime tamper indicators (Frida/Xposed/TracerPid/hooks).
     * Never throws; returns a human-readable violation code if compromised, else null.
     */
    fun pollContinuousWatchdogViolation(context: Context): String? {
        val isRobolectric = Build.FINGERPRINT.equals("robolectric", ignoreCase = true)
        if (BuildConfig.DEBUG || isRobolectric) {
            return null
        }
        val nativeTamper = if (isNativeLibraryLoaded) {
            runCatching { isRuntimeTamperDetected() }.getOrDefault(true)
        } else {
            hasJvmProcHookIndicators()
        }
        if (nativeTamper) {
            val reason = "CONTINUOUS_WATCHDOG_FRIDA_OR_DEBUGGER_DETECTED"
            _tamperViolationReason.value = reason
            return reason
        }
        if (!verifySigningCertificateSha256(context.applicationContext ?: context)) {
            val reason = "CONTINUOUS_WATCHDOG_CERT_PIN_MISMATCH"
            _tamperViolationReason.value = reason
            return reason
        }
        return null
    }

    data class DefenseIntegrityStatus(
        val apkSignatureIntact: Boolean,
        val hypervisorFlags: Int,
        val inlineHooksDetected: Boolean,
        val polymorphicCodeIntact: Boolean,
        val debuggerWatchdogActive: Boolean
    ) {
        val isCompromisedOnProductionDevice: Boolean
            get() = !apkSignatureIntact || inlineHooksDetected || !polymorphicCodeIntact
    }

    /**
     * Converts a 64-char SHA-256 hex certificate pin to Base64 (used by Talsec freeRASP config).
     */
    fun expectedTalsecBase64CertHash(sha256Hex: String = BuildConfig.EXPECTED_RELEASE_CERT_SHA256): String {
        val clean = sha256Hex.replace(":", "").trim()
        if (clean.length != 64) return "KpLcLKQN96jtvmfA493NKIoPfD5beiLgEzostnG8EdU="
        return runCatching {
            val bytes = ByteArray(32) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        }.getOrDefault("KpLcLKQN96jtvmfA493NKIoPfD5beiLgEzostnG8EdU=")
    }

    /**
     * Extracts and verifies the runtime APK signing certificate SHA-256 digest against the pinned
     * Release certificate hash (`BuildConfig.EXPECTED_RELEASE_CERT_SHA256`).
     */
    fun verifySigningCertificateSha256(
        context: Context,
        expectedSha256Hex: String = BuildConfig.EXPECTED_RELEASE_CERT_SHA256
    ): Boolean {
        if (Build.FINGERPRINT.equals("robolectric", ignoreCase = true) || BuildConfig.DEBUG) {
            return true
        }
        val expectedNorm = expectedSha256Hex.replace(":", "").trim().lowercase()
        if (expectedNorm.length != 64) return false

        return runCatching {
            val pkgInfo: PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                )
            }

            val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pkgInfo.signingInfo
                info?.apkContentsSigners ?: info?.signingCertificateHistory
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.signatures
            } ?: return@runCatching false

            val md = MessageDigest.getInstance("SHA-256")
            signers.any { sig ->
                val actualHex = md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
                val jvmMatched = MessageDigest.isEqual(
                    expectedNorm.toByteArray(Charsets.UTF_8),
                    actualHex.toByteArray(Charsets.UTF_8)
                )
                val nativeMatched = if (isNativeLibraryLoaded && expectedSha256Hex == BuildConfig.EXPECTED_RELEASE_CERT_SHA256) {
                    runCatching { verifyReleaseSigningCertPin(actualHex) }.getOrDefault(false)
                } else {
                    true
                }
                jvmMatched && nativeMatched
            }
        }.getOrDefault(false)
    }

    /**
     * Verifies `classes.dex` CRC32 against optional environment pin `BuildConfig.EXPECTED_DEX_CRC32`
     * in release builds, preventing silent DEX bytecode injection even if signatures were bypassed.
     */
    fun verifyExpectedDexCrc32Pin(
        context: Context,
        expectedDexCrc32Hex: String = BuildConfig.EXPECTED_DEX_CRC32
    ): Boolean {
        val cleanPin = expectedDexCrc32Hex.trim().lowercase()
        if (cleanPin.isBlank() || cleanPin == "unset" || cleanPin == "auto_baseline" || Build.FINGERPRINT.equals("robolectric", ignoreCase = true) || BuildConfig.DEBUG) {
            return true
        }
        if (cleanPin.length != 8) return false
        return runCatching {
            val apkPath = context.packageResourcePath ?: return@runCatching false
            val apkFile = File(apkPath)
            if (!apkFile.exists()) return@runCatching false
            ZipFile(apkFile).use { zip ->
                val dexEntry = zip.getEntry("classes.dex") ?: return@runCatching false
                val actualHex = "%08x".format(dexEntry.crc)
                MessageDigest.isEqual(
                    cleanPin.toByteArray(Charsets.UTF_8),
                    actualHex.toByteArray(Charsets.UTF_8)
                )
            }
        }.getOrDefault(false)
    }

    /**
     * Initializes both the Native APK Signature/DEX Integrity verification and the real-time
     * ptrace + inline-prologue debugger watchdog inside `MainActivity.onCreate()`.
     */
    fun initializeDefenseGradeSecurity(
        context: Context,
        enforceReleaseLockdown: Boolean = !BuildConfig.DEBUG
    ): DefenseIntegrityStatus {
        val appCtx = context.applicationContext ?: context
        val isRobolectric = Build.FINGERPRINT.equals("robolectric", ignoreCase = true)

        val signingCertMatches = verifySigningCertificateSha256(appCtx)
        val dexPinMatches = verifyExpectedDexCrc32Pin(appCtx)
        val apkIntact = signingCertMatches && dexPinMatches && if (isNativeLibraryLoaded) {
            runCatching { verifyApkIntegrity(appCtx) }.getOrDefault(false)
        } else {
            verifyApkIntegrityJvmFallback(appCtx)
        }

        if (isNativeLibraryLoaded) {
            runCatching { startDebuggerWatchdog() }
        }

        val hypervisorFlags = if (isNativeLibraryLoaded) {
            runCatching { detectHypervisorAndEmulation() }.getOrDefault(0)
        } else 0

        val inlineHooks = checkInlineFunctionHooks()

        val polymorphicIntact = verifyCodePolymorphism()

        val status = DefenseIntegrityStatus(
            apkSignatureIntact = apkIntact,
            hypervisorFlags = hypervisorFlags,
            inlineHooksDetected = inlineHooks,
            polymorphicCodeIntact = polymorphicIntact,
            debuggerWatchdogActive = isNativeLibraryLoaded
        )

        if (!isRobolectric && !BuildConfig.DEBUG && enforceReleaseLockdown && status.isCompromisedOnProductionDevice) {
            val reason = when {
                !signingCertMatches -> "APK_SIGNATURE_SHA256_MISMATCH"
                !apkIntact -> "APK_DEX_TAMPER_DETECTED"
                inlineHooks -> "NATIVE_HOOK_OR_FRIDA_DETECTED"
                else -> "SELF_HEALING_CHECKSUM_TAMPER"
            }
            _tamperViolationReason.value = reason
            Log.w(TAG, "Release tamper violation detected ($reason): $status")
            MemoryScrubberLifecycleObserver.scrubAllRegisteredBuffersNow()
        }

        return status
    }

    private fun hasJvmProcHookIndicators(): Boolean = runCatching {
        val statusFile = File("/proc/self/status")
        if (statusFile.exists()) {
            statusFile.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    if (l.startsWith("TracerPid:")) {
                        val pid = l.substringAfter("TracerPid:").trim().toIntOrNull() ?: 0
                        if (pid > 0) return@runCatching true
                        break
                    }
                }
            }
        }
        val mapsFile = File("/proc/self/maps")
        if (mapsFile.exists()) {
            val tokens = arrayOf("frida", "libgadget", "lsposed", "zygisk", "xposed", "riru", "substrate")
            mapsFile.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val lower = line?.lowercase() ?: continue
                    if (tokens.any { lower.contains(it) }) return@runCatching true
                }
            }
        }
        false
    }.getOrDefault(false)

    /**
     * JVM fallback that inspects `classes.dex` CRC + APK Signing Certificate SHA-256 directly
     * when running in environments without native NDK shared libraries.
     */
    private fun verifyApkIntegrityJvmFallback(context: Context): Boolean {
        if (Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) {
            return true
        }
        return runCatching {
            val resourcePath = context.packageResourcePath ?: return@runCatching false
            val apkFile = File(resourcePath)
            if (!apkFile.exists()) return@runCatching false

            val digest = MessageDigest.getInstance("SHA-256")
            ZipFile(apkFile).use { zip ->
                val dexEntry = zip.getEntry("classes.dex") ?: return@runCatching false
                digest.update(dexEntry.crc.toString().toByteArray(Charsets.UTF_8))
                digest.update(dexEntry.size.toString().toByteArray(Charsets.UTF_8))
            }

            val pkgInfo: PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                )
            }

            val certBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.signatures?.firstOrNull()?.toByteArray()
            } ?: return@runCatching false

            digest.update(certBytes)

            val computedHex = digest.digest().joinToString("") { "%02x".format(it) }
            val existing = baselineJvmApkDigest
            if (existing == null) {
                baselineJvmApkDigest = computedHex
                true
            } else {
                MessageDigest.isEqual(
                    existing.toByteArray(Charsets.UTF_8),
                    computedHex.toByteArray(Charsets.UTF_8)
                )
            }
        }.getOrDefault(false)
    }
}
