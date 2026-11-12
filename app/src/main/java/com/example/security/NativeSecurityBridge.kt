package com.example.security

import android.content.Context
import androidx.annotation.Keep
import com.waqar.institute.security.NativeSecurityBridge as WaqarNativeSecurityBridge
import org.lsposed.lsparanoid.Obfuscate

/**
 * Dynamic JNI `RegisterNatives` Bridge (`com.example.security.NativeSecurityBridge`).
 *
 * All native methods in this object are bound dynamically inside C++20 `JNI_OnLoad`
 * (`app/src/main/cpp/native-security.cpp`) with `-fvisibility=hidden -s -flto`,
 * leaving zero exported `Java_com_example_...` symbols in `.dynsym` when inspected
 * in IDA Pro, Ghidra, or MT Manager.
 */
@Obfuscate
@Keep
object NativeSecurityBridge {
    private var nativeLoaded = false

    init {
        nativeLoaded = runCatching {
            System.loadLibrary("waqar_native_security")
            true
        }.getOrDefault(false)
    }

    @Keep
    @JvmStatic
    external fun verifyEnvironmentIntegrity(context: Context): Boolean

    @Keep
    @JvmStatic
    external fun deriveKeyMaterial(): ByteArray

    fun isEnvironmentIntact(context: Context): Boolean {
        return if (nativeLoaded) {
            runCatching { verifyEnvironmentIntegrity(context.applicationContext) }
                .getOrElse { WaqarNativeSecurityBridge.initializeDefenseGradeSecurity(context).apkSignatureIntact }
        } else {
            WaqarNativeSecurityBridge.initializeDefenseGradeSecurity(context).apkSignatureIntact
        }
    }

    fun getHardwareDerivedKeyMask(): ByteArray {
        return if (nativeLoaded) {
            runCatching { deriveKeyMaterial() }.getOrElse { ByteArray(32) }
        } else {
            ByteArray(32)
        }
    }
}
