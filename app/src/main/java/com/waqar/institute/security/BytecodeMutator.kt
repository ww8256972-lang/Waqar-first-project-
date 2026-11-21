package com.waqar.institute.security

import android.content.Context

/**
 * Self-Mutating Polymorphic Shield (`BytecodeMutator.kt` & `PolymorphicShieldEngine.cpp`).
 *
 * Monitors instruction pointer execution flows across native polymorphic dispatch blocks
 * and mutates runtime byte buffers with epoch-keyed polymorphic keystreams. If single-step
 * debugging or instruction pointer stalling is detected, triggers immediate hardware key purge.
 */
object BytecodeMutator {

    private var nativeLoaded: Boolean = false

    init {
        nativeLoaded = runCatching {
            System.loadLibrary("waqar_native_security")
            true
        }.getOrDefault(false)
    }

    private external fun nativeVerifyInstructionPointerFlow(): Boolean
    private external fun nativePolymorphicTransform(inputBytes: ByteArray, epochSalt: Int): ByteArray

    fun verifyExecutionFlowAndMutate(context: Context, payload: ByteArray, epochSalt: Int): ByteArray {
        val flowHealthy = isInstructionPointerFlowHealthy()
        if (!flowHealthy) {
            EnterpriseRaspShield.triggerSelfDestructKeyWipe(context)
            MemoryScrubberManager.scrubByteArray(payload)
            return ByteArray(0)
        }
        return if (nativeLoaded) {
            runCatching { nativePolymorphicTransform(payload, epochSalt) }
                .getOrElse { ByteArray(0) }
        } else {
            polymorphicTransformJvm(payload, epochSalt)
        }
    }

    fun isInstructionPointerFlowHealthy(): Boolean {
        return if (nativeLoaded) {
            runCatching { nativeVerifyInstructionPointerFlow() }.getOrDefault(false)
        } else {
            verifyJvmInstructionFlow()
        }
    }

    private fun verifyJvmInstructionFlow(): Boolean {
        val startNs = System.nanoTime()
        var acc = 0x13579BDFu
        for (i in 0u until 512u) {
            acc = ((acc shl 7) or (acc shr 25)) xor (i * 0x9E3779B9u)
        }
        val elapsedNs = System.nanoTime() - startNs
        return !(elapsedNs > 90_000_000L && acc != 0u)
    }

    private fun polymorphicTransformJvm(input: ByteArray, epochSalt: Int): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        var state = (epochSalt.toUInt() xor 0x6A09E667u)
        val out = ByteArray(input.size)
        for (i in input.indices) {
            state = ((state shl 7) or (state shr 25)) xor (0xA5A5F00Du + i.toUInt())
            val ks = ((state shr ((i and 3) * 8)) and 0xFFu).toInt()
            out[i] = ((input[i].toInt() and 0xFF) xor ks).toByte()
        }
        return out
    }
}
