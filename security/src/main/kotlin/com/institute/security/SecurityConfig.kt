package com.institute.security

data class SecurityConfig(
    val gatewayBaseUrl: String,
    val tlsPins: List<String>,
    val cloudProjectNumber: Long,
    val pqcKid: String,
    val pqcMlKemPub: ByteArray,
    val pqcX25519Pub: ByteArray,
)
