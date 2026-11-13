import java.util.Properties

plugins {
    id("com.android.library")
}

val sec = Properties().apply {
    file("security.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

fun prop(key: String, fallback: String = ""): String =
    sec.getProperty(key, fallback).replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.institute.security"
    compileSdk { version = release(36) { minorApiLevel = 1 } }

    defaultConfig {
        minSdk = 24

        buildConfigField("String", "GATEWAY_URL", "\"${prop("gateway.url", "https://secure.yourinstitute.in")}\"")
        buildConfigField("String", "TLS_PINS", "\"${prop("tls.pins")}\"")
        buildConfigField("String", "PLAY_CLOUD_PROJECT", "\"${prop("play.cloudProject", "0")}\"")
        buildConfigField("String", "PQC_KID", "\"${prop("pqc.kid", "k-2026-11")}\"")
        buildConfigField("String", "PQC_MLKEM_PUB", "\"${prop("pqc.mlkem.pub")}\"")
        buildConfigField("String", "PQC_X25519_PUB", "\"${prop("pqc.x25519.pub")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets["main"].kotlin.srcDir("../shared-crypto/src/main/kotlin")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Versions aligned identically with gradle/libs.versions.toml to avoid upgrading app dependencies
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    implementation("com.squareup.okhttp3:okhttp:4.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.startup:startup-runtime:1.2.0")
    implementation("com.google.android.play:integrity:1.4.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.10.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.json:json:20240303")
}
