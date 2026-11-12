package com.waqar.institute.security

import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Native Anti-Frida, Xposed, LSPosed, Zygisk & Dynamic Hook Detection Engine.
 *
 * Bridges with `FridaShieldDetector.cpp` (`libwaqar_native_security.so`) and performs multi-layer
 * user-space + kernel `/proc/self/maps`, `/proc/self/task/<tid>/comm`, D-Bus port `27042`/`27043`,
 * and stack-frame instrumentation checks.
 */
object AntiHookManager {

    private var nativeLoaded = false

    init {
        try {
            System.loadLibrary("waqar_native_security")
            nativeLoaded = true
        } catch (_: UnsatisfiedLinkError) {
            nativeLoaded = false
        }
    }

    data class HookInspectionReport(
        val isClean: Boolean,
        val fridaServerDetected: Boolean,
        val xposedOrLsposedDetected: Boolean,
        val inlineTrampolineDetected: Boolean,
        val suspiciousLibraryName: String? = null
    )

    private external fun nativeScanFridaAndHooks(): Int

    fun runComprehensiveHookScan(): HookInspectionReport {
        val nativeFlags = if (nativeLoaded) {
            runCatching { nativeScanFridaAndHooks() }.getOrDefault(0)
        } else {
            0
        }

        val nativeInlineHook = NativeSecurityBridge.checkInlineFunctionHooks()
        val mapsMatch = scanProcSelfMapsForHooks()
        val stackHook = detectXposedOrFridaInStackTrace()
        val fridaPortOpen = isLocalFridaPortListening()

        val fridaDetected = (nativeFlags and 0x01) != 0 || fridaPortOpen || (mapsMatch?.contains("frida") == true)
        val xposedDetected = (nativeFlags and 0x02) != 0 || stackHook || (mapsMatch != null && !mapsMatch.contains("frida"))
        val trampolineDetected = (nativeFlags and 0x04) != 0 || nativeInlineHook

        val isClean = !fridaDetected && !xposedDetected && !trampolineDetected
        return HookInspectionReport(
            isClean = isClean,
            fridaServerDetected = fridaDetected,
            xposedOrLsposedDetected = xposedDetected,
            inlineTrampolineDetected = trampolineDetected,
            suspiciousLibraryName = mapsMatch
        )
    }

    private fun scanProcSelfMapsForHooks(): String? {
        val tokens = listOf(
            "frida-agent",
            "frida-gadget",
            "libfrida",
            "xposedbridge",
            "lsposed",
            "edxposed",
            "zygisk",
            "riru",
            "substrate",
            "libdobby"
        )
        return runCatching {
            val mapsFile = File("/proc/self/maps")
            if (!mapsFile.exists()) return@runCatching null
            BufferedReader(FileReader(mapsFile)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val lower = line!!.lowercase()
                    val hit = tokens.firstOrNull { lower.contains(it) }
                    if (hit != null) return@use hit
                }
                null
            }
        }.getOrNull()
    }

    private fun detectXposedOrFridaInStackTrace(): Boolean {
        return runCatching {
            val traces = Throwable().stackTrace
            traces.any { element ->
                val cls = element.className.lowercase()
                cls.contains("de.robv.android.xposed") ||
                    cls.contains("lsposed") ||
                    cls.contains("com.saurik.substrate") ||
                    cls.contains("frida")
            }
        }.getOrDefault(false)
    }

    private fun isLocalFridaPortListening(): Boolean {
        // Avoid opening localhost TCP sockets on the UI thread (prevents NetworkOnMainThreadException and startup stalls)
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            return false
        }
        val ports = intArrayOf(27042, 27043)
        for (port in ports) {
            val open = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 35)
                    true
                }
            }.getOrDefault(false)
            if (open) return true
        }
        return false
    }
}
