package com.waqar.institute.ipc

import androidx.annotation.Keep
import org.lsposed.lsparanoid.Obfuscate

@Obfuscate
@Keep
object ZeroCopyIpcRingManager {
    private var nativeLoaded = false

    init {
        nativeLoaded = runCatching {
            System.loadLibrary("waqar_native_security")
            true
        }.getOrDefault(false)
    }

    @Keep
    private external fun nativePublishZeroCopyFrame(frameBytes: ByteArray): Long

    fun publishPortalEventZeroCopy(portalTag: String, eventSummary: String): Long {
        if (!nativeLoaded) return -1L
        val payload = "$portalTag|$eventSummary|${System.nanoTime()}".toByteArray(Charsets.UTF_8)
        return runCatching { nativePublishZeroCopyFrame(payload) }.getOrDefault(-1L)
    }
}
