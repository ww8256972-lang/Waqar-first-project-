package com.waqar.institute.sandbox

import androidx.annotation.Keep
import org.lsposed.lsparanoid.Obfuscate

@Obfuscate
@Keep
object MicroVmExamSandboxManager {
    init {
        runCatching { System.loadLibrary("waqar_native_security") }
    }

    @Keep
    private external fun nativeEnterMicroVmExamSandbox(): Boolean

    fun activateIsolatedExamMicroVm(): Boolean {
        return runCatching { nativeEnterMicroVmExamSandbox() }.getOrDefault(true)
    }
}
