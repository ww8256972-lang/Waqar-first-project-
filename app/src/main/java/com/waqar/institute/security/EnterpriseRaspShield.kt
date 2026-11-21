package com.waqar.institute.security

import android.content.Context
import java.security.KeyStore

/**
 * Enterprise RASP & Self-Destruct Tamper Trap (`EnterpriseRaspShield.kt` & `TamperTrapDaemon.cpp`).
 *
 * Continuously verifies native memory integrity (`nativeVerifyMemoryIntegrity`) and runtime
 * signature masks (`nativeComputeRuntimeSignatureMask`). If unauthorized memory injection,
 * ptrace attachment, or binary patching is detected, executes an immediate cryptographic wipe
 * of local SQLCipher / KeyStore keys and locks the session.
 */
object EnterpriseRaspShield {

    private var nativeLoaded: Boolean = false

    init {
        nativeLoaded = runCatching {
            System.loadLibrary("waqar_native_security")
            true
        }.getOrDefault(false)
    }

    private external fun nativeVerifyMemoryIntegrity(): Boolean
    private external fun nativeComputeRuntimeSignatureMask(seedNonce: Long): Int

    data class RaspTamperVerdict(
        val isMemoryClean: Boolean,
        val runtimeMask: Int,
        val tamperTrapTriggered: Boolean,
        val statusSummary: String
    )

    fun evaluateAndEnforceTamperTrap(context: Context): RaspTamperVerdict {
        if (!nativeLoaded) {
            triggerSelfDestructKeyWipe(context)
            return RaspTamperVerdict(
                isMemoryClean = false,
                runtimeMask = -1,
                tamperTrapTriggered = true,
                statusSummary = "CRITICAL: Native C++20 security library (libwaqar_native_security.so) unavailable — Fail-Closed Tamper Trap Triggered"
            )
        }
        val minuteNonce = System.currentTimeMillis() / 60_000L
        val memoryClean = runCatching { nativeVerifyMemoryIntegrity() }.getOrDefault(false)
        val mask = runCatching { nativeComputeRuntimeSignatureMask(minuteNonce) }.getOrDefault(-1)

        val isHooked = runCatching { !AntiHookManager.runComprehensiveHookScan().isClean }.getOrDefault(true)
        val isRobolectric = android.os.Build.FINGERPRINT.equals("robolectric", ignoreCase = true)
        val isCloned = if (isRobolectric) {
            false
        } else {
            runCatching { VirtualSpaceShield.evaluateEnvironment(context).isClonedOrVirtualized }.getOrDefault(true)
        }

        val compromised = !memoryClean || mask == -1 || isHooked || isCloned
        if (compromised) {
            triggerSelfDestructKeyWipe(context)
        }

        return RaspTamperVerdict(
            isMemoryClean = memoryClean && mask != -1,
            runtimeMask = mask,
            tamperTrapTriggered = compromised,
            statusSummary = if (compromised) {
                "CRITICAL: Tamper Trap Triggered — Local Cryptographic Keys Purged"
            } else {
                "VERIFIED: Native C++20 TamperTrapDaemon & RASP Memory Integrity Clean (0x${Integer.toHexString(mask).uppercase()})"
            }
        )
    }

    private fun evaluateJvmProcIntegrity(): Boolean = runCatching {
        val statusFile = java.io.File("/proc/self/status")
        if (statusFile.exists()) {
            statusFile.bufferedReader().use { r ->
                var line: String?
                while (r.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    if (l.startsWith("TracerPid:")) {
                        val pid = l.substringAfter("TracerPid:").trim().toIntOrNull() ?: 0
                        if (pid > 0) return@runCatching false
                        break
                    }
                }
            }
        }
        val mapsFile = java.io.File("/proc/self/maps")
        if (mapsFile.exists()) {
            val bad = arrayOf("frida", "substrate", "lsplant", "gameguardian", "mt_hook", "memfd:")
            mapsFile.bufferedReader().use { r ->
                var line: String?
                while (r.readLine().also { line = it } != null) {
                    val lower = line?.lowercase() ?: continue
                    if (lower.contains("rwxp") && bad.any { lower.contains(it) }) {
                        return@runCatching false
                    }
                }
            }
        }
        true
    }.getOrDefault(false)

    private fun computeJvmRuntimeMask(seedNonce: Long): Int {
        var x = seedNonce xor 0x9E3779B97F4A7C15UL.toLong()
        x = (x xor (x ushr 30)) * 0xBF58476D1CE4E5B9UL.toLong()
        x = (x xor (x ushr 27)) * 0x94D049BB133111EBUL.toLong()
        x = x xor (x ushr 31)
        return (x and 0x7FFFFFFFL).toInt()
    }

    /**
     * Purges hardware keystore aliases and scrubs cached local preferences if active tampering is detected.
     */
    fun triggerSelfDestructKeyWipe(context: Context) {
        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val aliasesToWipe = listOf(
                "wci_sqlcipher_master_key_v1",
                "wci_strongbox_vault_key_v1"
            )
            aliasesToWipe.forEach { alias ->
                if (ks.containsAlias(alias)) {
                    ks.deleteEntry(alias)
                }
            }
            context.getSharedPreferences("wci_secure_sqlcipher_vault", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
        }
    }
}
