package com.waqar.institute.ipc

import androidx.annotation.Keep
import org.lsposed.lsparanoid.Obfuscate

@Obfuscate
@Keep
object ZeroCopyIpcRingManager {
    init {
        runCatching { System.loadLibrary("waqar_native_security") }
    }

    @Keep
    private external fun nativePublishZeroCopyFrame(frameBytes: ByteArray): Long

    fun publishPortalEventZeroCopy(portalTag: String, eventSummary: String): Long {
        val payload = "$portalTag|$eventSummary|${System.nanoTime()}".toByteArray(Charsets.UTF_8)
        return runCatching { nativePublishZeroCopyFrame(payload) }.getOrDefault(1L)
    }
}
