package com.institute.gateway

import io.ktor.server.application.ApplicationCall

enum class Tier {
    UNTRUSTED,
    BASIC,
    DEVICE,
    STRONG,
}

class Policy(
    val minTier: Tier,
    val relation: String? = null,
    val maxIntegrityAgeSec: Long = 3600L,
    val requireHardwareAttestation: Boolean = false,
    val enforceOverride: Boolean? = null,
    val objectOf: ((ApplicationCall) -> String)? = null,
)

class Denied(val status: Int, val msg: String) : RuntimeException(msg)

data class Principal(
    val userId: String,
    val deviceId: String,
    val tier: Tier,
)
