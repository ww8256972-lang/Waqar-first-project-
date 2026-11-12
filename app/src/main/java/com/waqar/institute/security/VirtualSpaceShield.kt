package com.waqar.institute.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process

/**
 * Virtualization, Parallel Space, Dual App & Clone Environment Blocker.
 *
 * Prevents students or attackers from running Waqar Computer Institute ERP inside
 * Multi-Account Cloners (Parallel Space, VirtualXposed, DualSpace, 2Accounts, VirtualApp,
 * Island, Shelter) to bypass single-device attendance/exam locks.
 */
object VirtualSpaceShield {

    private const val EXPECTED_PACKAGE = "com.aistudio.waqarinstitute.wciapp"

    private val KNOWN_CLONER_PACKAGES = listOf(
        "com.lbe.parallel.intl",
        "com.parallel.space.lite",
        "io.va.exposed",
        "io.virtualapp",
        "com.excelliance.dualaid",
        "com.ludashi.dualspace",
        "com.polestar.super.clone",
        "com.oasisfeng.island",
        "net.typeblog.shelter",
        "dkplugin.pke.nnp"
    )

    private val SUSPICIOUS_PATH_SEGMENTS = listOf(
        "parallel",
        "virtual",
        "dualspace",
        "multidroid",
        "vxp",
        "clone",
        "999" // Common cloned user profile ID on modified ROMs when unauthorized
    )

    data class VirtualSpaceVerdict(
        val isClonedOrVirtualized: Boolean,
        val canonicalDataDir: String,
        val violationReason: String? = null
    )

    fun evaluateEnvironment(context: Context): VirtualSpaceVerdict {
        val filesDirPath = runCatching { context.filesDir.canonicalPath }.getOrDefault(context.filesDir.absolutePath)
        val pkgName = context.packageName

        // 1. Verify package name has not been rewritten by a wrapper container
        if (pkgName != EXPECTED_PACKAGE && !pkgName.startsWith("com.example")) {
            return VirtualSpaceVerdict(
                isClonedOrVirtualized = true,
                canonicalDataDir = filesDirPath,
                violationReason = "Tampered package identity: $pkgName"
            )
        }

        // 2. Inspect canonical private data path for nested host container directories
        // Standard path: /data/user/0/<pkg>/files or /data/data/<pkg>/files
        val lowerPath = filesDirPath.lowercase()
        val suspiciousSegment = SUSPICIOUS_PATH_SEGMENTS.firstOrNull { token ->
            lowerPath.contains("/$token") || lowerPath.contains("${token}_")
        }
        if (suspiciousSegment != null) {
            return VirtualSpaceVerdict(
                isClonedOrVirtualized = true,
                canonicalDataDir = filesDirPath,
                violationReason = "Virtual container path detected ($suspiciousSegment)"
            )
        }

        // 3. Check if the path contains another package name before our own package name
        val expectedDataTail = "/$pkgName/files"
        if (!filesDirPath.endsWith(expectedDataTail)) {
            return VirtualSpaceVerdict(
                isClonedOrVirtualized = true,
                canonicalDataDir = filesDirPath,
                violationReason = "Non-standard sandbox directory: $filesDirPath"
            )
        }

        // 4. Verify UID ownership matches ApplicationInfo UID
        val myUid = Process.myUid()
        val appInfoUid = context.applicationInfo.uid
        if (myUid != appInfoUid) {
            return VirtualSpaceVerdict(
                isClonedOrVirtualized = true,
                canonicalDataDir = filesDirPath,
                violationReason = "Process UID ($myUid) mismatch with ApplicationInfo UID ($appInfoUid)"
            )
        }

        return VirtualSpaceVerdict(
            isClonedOrVirtualized = false,
            canonicalDataDir = filesDirPath,
            violationReason = null
        )
    }

    fun scanInstalledClonerContainers(context: Context): List<String> {
        val pm = context.packageManager
        return KNOWN_CLONER_PACKAGES.filter { pkg ->
            runCatching {
                pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
                true
            }.getOrDefault(false)
        }
    }
}
