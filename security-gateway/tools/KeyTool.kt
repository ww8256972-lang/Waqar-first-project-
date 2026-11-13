package com.institute.gateway.tools

import com.institute.crypto.HybridKem
import java.io.File
import java.util.Base64

/**
 * One-time PQC keypair generation utility (Section 7.3 / 7.4).
 * Generates ML-KEM-768 + X25519 keys, prints public Base64 properties for security/security.properties,
 * and writes private key bytes to keys.bin for KMS wrapping / secret injection (never commit keys.bin!).
 */
object KeyTool {
    @JvmStatic
    fun main(args: Array<String>) {
        val kid = args.firstOrNull()?.takeIf { it.isNotBlank() } ?: "k-2026-11"
        val outFile = File(args.getOrNull(1) ?: "keys.bin")

        val (pub, priv) = HybridKem.generateRecipient(kid)
        val b64 = Base64.getEncoder()

        // Binary layout: [mlkem_priv (2400B)][x25519_priv (32B)]
        outFile.writeBytes(priv.mlkem + priv.x25519)

        println("# Add to security/security.properties (public keys only):")
        println("pqc.kid=$kid")
        println("pqc.mlkem.pub=${b64.encodeToString(pub.mlkem)}")
        println("pqc.x25519.pub=${b64.encodeToString(pub.x25519)}")
        println()
        println("# Wrote ${priv.mlkem.size + priv.x25519.size} private key bytes to ${outFile.absolutePath}")
        println("# Store encrypted/wrapped via KMS or secret manager, then immediately run: shred -u ${outFile.name}")
    }
}
