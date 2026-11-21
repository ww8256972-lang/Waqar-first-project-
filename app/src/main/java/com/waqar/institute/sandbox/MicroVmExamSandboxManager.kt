package com.waqar.institute.sandbox

import androidx.annotation.Keep
import org.lsposed.lsparanoid.Obfuscate

@Obfuscate
@Keep
object MicroVmExamSandboxManager {
    private var nativeLoaded = false

    init {
        nativeLoaded = runCatching {
            System.loadLibrary("waqar_native_security")
            true
        }.getOrDefault(false)
    }

    @Keep
    private external fun nativeEnterMicroVmExamSandbox(): Boolean

    fun activateIsolatedExamMicroVm(): Boolean {
        if (!nativeLoaded) return false
        return runCatching { nativeEnterMicroVmExamSandbox() }.getOrDefault(false)
    }
}
