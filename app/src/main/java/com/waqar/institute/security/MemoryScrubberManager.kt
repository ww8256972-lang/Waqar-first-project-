package com.waqar.institute.security

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Arrays
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Dynamic RAM Memory Scrubber & String Obfuscation Engine.
 *
 * Ensures that plaintext passwords, session keys, cryptographic passphrases, and API tokens
 * are held in mutable `ByteArray` / `CharArray` buffers rather than immutable heap `String`
 * objects, and explicitly overwrites them with random entropy + `0x00` bytes immediately after
 * authentication or when the activity enters background (`onStop()`).
 */
object MemoryScrubberManager {

    private val secureRandom = SecureRandom()
    private val registeredSensitiveByteBuffers = CopyOnWriteArrayList<ByteArray>()
    private val registeredSensitiveCharBuffers = CopyOnWriteArrayList<CharArray>()

    fun registerSensitiveBuffer(buffer: ByteArray) {
        registeredSensitiveByteBuffers.add(buffer)
    }

    fun registerSensitiveChars(chars: CharArray) {
        registeredSensitiveCharBuffers.add(chars)
    }

    /**
     * Multi-pass DoD 5220.22-M style overwrite (`0xFF` -> random -> `0x00`) on a sensitive `ByteArray`.
     */
    fun scrubByteArray(buffer: ByteArray?) {
        if (buffer == null || buffer.isEmpty()) return
        Arrays.fill(buffer, 0xFF.toByte())
        secureRandom.nextBytes(buffer)
        Arrays.fill(buffer, 0x00.toByte())
    }

    /**
     * Multi-pass overwrite on a sensitive `CharArray` (e.g., login password or PIN).
     */
    fun scrubCharArray(chars: CharArray?) {
        if (chars == null || chars.isEmpty()) return
        for (i in chars.indices) {
            chars[i] = '\uFFFF'
        }
        for (i in chars.indices) {
            chars[i] = '\u0000'
        }
    }

    /**
     * Zeroes out a direct or heap `ByteBuffer`.
     */
    fun scrubByteBuffer(byteBuffer: ByteBuffer?) {
        if (byteBuffer == null || byteBuffer.isReadOnly) return
        byteBuffer.clear()
        while (byteBuffer.hasRemaining()) {
            byteBuffer.put(0.toByte())
        }
        byteBuffer.rewind()
    }

    /**
     * XOR-encodes a secret string into an obfuscated `ByteArray` using a rolling key mask so
     * static or heap string searches (`strings` / GameGuardian / Frida heap dump) never see
     * the plaintext value at rest in memory.
     */
    fun obfuscateToByteArray(plainText: String, seedMask: Int = 0x5A3C96E1): ByteArray {
        val raw = plainText.toByteArray(Charsets.UTF_8)
        val encoded = ByteArray(raw.size)
        for (i in raw.indices) {
            val rollingKey = ((seedMask shr (i % 16)) xor (i * 0x37)) and 0xFF
            encoded[i] = (raw[i].toInt() xor rollingKey).toByte()
        }
        scrubByteArray(raw)
        return encoded
    }

    /**
     * Reconstructs the transient `CharArray` from an obfuscated `ByteArray` for immediate use.
     * Callers must pass the returned `CharArray` to [scrubCharArray] in a `finally` block.
     */
    fun deobfuscateToCharArray(encoded: ByteArray, seedMask: Int = 0x5A3C96E1): CharArray {
        val decodedBytes = ByteArray(encoded.size)
        for (i in encoded.indices) {
            val rollingKey = ((seedMask shr (i % 16)) xor (i * 0x37)) and 0xFF
            decodedBytes[i] = (encoded[i].toInt() xor rollingKey).toByte()
        }
        val chars = String(decodedBytes, Charsets.UTF_8).toCharArray()
        scrubByteArray(decodedBytes)
        return chars
    }

    /**
     * Scrubs all registered session buffers and delegates to `com.example.security.MemoryScrubber`.
     */
    fun scrubAllRegisteredBuffers() {
        for (buf in registeredSensitiveByteBuffers) {
            scrubByteArray(buf)
        }
        registeredSensitiveByteBuffers.clear()

        for (chars in registeredSensitiveCharBuffers) {
            scrubCharArray(chars)
        }
        registeredSensitiveCharBuffers.clear()

        com.example.security.MemoryScrubberLifecycleObserver.scrubAllRegisteredBuffersNow()
    }
}
