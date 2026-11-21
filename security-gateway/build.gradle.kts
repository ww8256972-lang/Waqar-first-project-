plugins {
    kotlin("jvm") version "2.2.10"
    kotlin("plugin.serialization") version "2.2.10"
    application
}

group = "com.institute.gateway"
version = "0.1.0"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

sourceSets {
    main {
        kotlin.srcDirs("src/main/kotlin", "../shared-crypto/src/main/kotlin", "tools")
    }
}

application {
    mainClass.set("com.institute.gateway.MainKt")
}

tasks.register<JavaExec>("runKeyTool") {
    group = "security"
    description = "Generate ML-KEM-768 + X25519 keypair and print public config"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.institute.gateway.tools.KeyTool")
}

dependencies {
    val ktorVersion = "3.6.1"

    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")

    implementation("io.ktor:ktor-client-cio-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation-jvm:$ktorVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    implementation("com.nimbusds:nimbus-jose-jwt:10.10")
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    implementation("com.google.auth:google-auth-library-oauth2-http:1.56.0")

    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.postgresql:postgresql:42.7.14")
    implementation("ch.qos.logback:logback-classic:1.6.6")

    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
    testImplementation("io.ktor:ktor-client-mock-jvm:$ktorVersion")
    testImplementation(kotlin("test"))
}
