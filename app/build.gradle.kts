import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Duration

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
  alias(libs.plugins.lsparanoid)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.waqarinstitute.wciapp"
    minSdk = 24
    targetSdk = 36
    val resolvedVersionCode = (
      System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        ?: project.findProperty("appVersionCode")?.toString()?.toIntOrNull()
        ?: 1
      ).coerceAtLeast(1)
    val resolvedVersionName = (
      System.getenv("GITHUB_REF_NAME")?.takeIf { it.startsWith("v") }?.removePrefix("v")
        ?: project.findProperty("appVersionName")?.toString()?.takeIf { it.isNotBlank() }
        ?: "1.0"
      )
    versionCode = resolvedVersionCode
    versionName = resolvedVersionName

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    val resolveCertSha256FromKeystore: () -> String? = {
      runCatching<String?> {
        val injectedStore = project.findProperty("android.injected.signing.store.file")?.toString()?.takeIf { it.isNotBlank() }
        val envStore = System.getenv("KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
        val defaultStore = "${rootDir}/my-upload-key.jks"
        val candidatePath = injectedStore ?: envStore ?: defaultStore
        val storeFile = file(candidatePath)
        if (!storeFile.exists() || !storeFile.isFile) {
          null
        } else {
          val storePass = (
            project.findProperty("android.injected.signing.store.password")?.toString()?.takeIf { it.isNotBlank() }
              ?: System.getenv("STORE_PASSWORD")?.takeIf { it.isNotBlank() }
              ?: "waqar_release_pass"
            ).toCharArray()
          val preferredAlias = project.findProperty("android.injected.signing.key.alias")?.toString()?.takeIf { it.isNotBlank() }
            ?: "upload"
          val ks = KeyStore.getInstance(KeyStore.getDefaultType())
          storeFile.inputStream().use { ks.load(it, storePass) }
          val cert = ks.getCertificate(preferredAlias)
            ?: ks.aliases().iterator().asSequence().mapNotNull { ks.getCertificate(it) }.firstOrNull()
          cert?.encoded?.let { encoded ->
            MessageDigest.getInstance("SHA-256")
              .digest(encoded)
              .joinToString("") { b -> "%02x".format(b) }
          }
        }
      }.getOrNull()
    }

    val expectedCertSha256 = (
      resolveCertSha256FromKeystore()?.takeIf { it.length == 64 }
        ?: System.getenv("EXPECTED_CERT_SHA256")?.takeIf { it.isNotBlank() }
        ?: project.findProperty("EXPECTED_CERT_SHA256")?.toString()?.takeIf { it.isNotBlank() }
        ?: "2a92dc2ca40d67a8edbe6740e3ddcd288a0f7c3e5b7a62e0133a2ce986f111d5"
      ).replace(":", "").trim().lowercase()
    buildConfigField("String", "EXPECTED_RELEASE_CERT_SHA256", "\"$expectedCertSha256\"")

    val expectedDexCrc32 = (
      System.getenv("EXPECTED_DEX_CRC32")?.takeIf { it.isNotBlank() }
        ?: project.findProperty("EXPECTED_DEX_CRC32")?.toString()?.takeIf { it.isNotBlank() }
        ?: ""
      ).trim().lowercase().let { if (it == "unset" || it == "auto_baseline") "" else it }

    val portalAuthTokenEndpointUrl = (
      System.getenv("PORTAL_AUTH_TOKEN_ENDPOINT_URL")?.takeIf { it.isNotBlank() }
        ?: project.findProperty("PORTAL_AUTH_TOKEN_ENDPOINT_URL")?.toString()?.takeIf { it.isNotBlank() }
        ?: "https://asia-east1-gen-lang-client-0309263247.cloudfunctions.net/issuePortalCustomToken"
      ).trim()
    buildConfigField("String", "PORTAL_AUTH_TOKEN_ENDPOINT_URL", "\"$portalAuthTokenEndpointUrl\"")

    val aiProxyEndpointUrl = (
      System.getenv("AI_PROXY_ENDPOINT_URL")?.takeIf { it.isNotBlank() }
        ?: project.findProperty("AI_PROXY_ENDPOINT_URL")?.toString()?.takeIf { it.isNotBlank() }
        ?: "https://asia-east1-gen-lang-client-0309263247.cloudfunctions.net/streamInstituteAiTutor"
      ).trim()
    buildConfigField("String", "AI_PROXY_ENDPOINT_URL", "\"$aiProxyEndpointUrl\"")

    ndk {
      abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
    }

    externalNativeBuild {
      cmake {
        cppFlags("-std=c++20 -fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections -fstack-protector-strong -O2")
        arguments(
          "-DANDROID_STL=c++_shared",
          "-DWCI_EXPECTED_CERT_SHA256=$expectedCertSha256",
          "-DWCI_EXPECTED_DEX_CRC32=$expectedDexCrc32"
        )
      }
    }
  }

  packaging {
    jniLibs {
      useLegacyPackaging = true
    }
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }

  // Wire CMake native C++20 security module when -PenableNativeCxxBuild=true (default true) and NDK is present
  val sdkRoot = System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME") ?: ""
  val ndkHome = System.getenv("ANDROID_NDK_HOME") ?: System.getenv("ANDROID_NDK_ROOT") ?: ""
  val hasNdkInstalled = (sdkRoot.isNotEmpty() && File(sdkRoot, "ndk").exists()) ||
    (sdkRoot.isNotEmpty() && File(sdkRoot, "ndk-bundle").exists()) ||
    (ndkHome.isNotEmpty() && File(ndkHome).exists())
  val enableNativeCxx = (project.findProperty("enableNativeCxxBuild")?.toString() ?: "true") == "true"
  if (enableNativeCxx && hasNdkInstalled) {
    externalNativeBuild {
      cmake {
        path = file("src/main/cpp/CMakeLists.txt")
        version = "3.22.1"
      }
    }
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  lint {
    abortOnError = true
    checkReleaseBuilds = true
  }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Configure KSP compiler arguments to eliminate Room schema warnings and guarantee incremental processing
ksp {
  arg("room.schemaLocation", "$projectDir/schemas")
  arg("room.incremental", "true")
  arg("room.expandProjection", "true")
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
  ignoreList.add("PORTAL_AUTH_TOKEN_ENDPOINT_URL")
  ignoreList.add("AI_PROXY_ENDPOINT_URL")
  ignoreList.add("GROK_API_KEY")
  ignoreList.add("GROK_API_URL")
  ignoreList.add("XAI_API_KEY")
  ignoreList.add("GROQ_API_KEY")
  ignoreList.add("CF_AI_GATEWAY_URL")
  ignoreList.add("CF_AI_TOKEN")
  ignoreList.add("ADMIN_BOOTSTRAP_PASSWORD")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.core)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.paging)
  implementation(libs.androidx.paging.runtime)
  implementation(libs.androidx.paging.compose)
  implementation(libs.androidx.profileinstaller)
  implementation(libs.androidx.biometric)
  implementation(libs.coil.compose)
  implementation(libs.firebase.appcheck.playintegrity)
  implementation(libs.firebase.ai)
  // Uncomment to use Firestore:
  implementation(libs.firebase.firestore)

  // Uncomment ALL FOUR of the following dependencies together to use Firebase Auth and Google
  // Sign-In via Credential Manager:
  implementation(libs.firebase.auth)
  implementation(libs.androidx.credentials)
  implementation(libs.androidx.credentials.play.services)
  implementation(libs.googleid)
  debugImplementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.okhttp)
  implementation(libs.play.services.location)
  implementation(libs.firebase.config)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.ui)
  implementation(libs.agora.full.sdk)
  implementation(libs.mlkit.face.detection)
  implementation(libs.mlkit.barcode.scanning)
  implementation(libs.tensorflow.lite)
  implementation(libs.google.play.integrity)

  // Enterprise Security & Performance Libraries (9-Library Blueprint):
  implementation(libs.google.tink.android)
  implementation(libs.talsec.freerasp)
  implementation(libs.scottyab.rootbeer)
  implementation(libs.appmattus.ct)
  implementation(libs.lsparanoid.core)
  implementation(libs.bouncycastle.prov)
  implementation(libs.sqlcipher.android)
  implementation(libs.androidx.sqlite.ktx)
  debugImplementation(libs.chucker.library)
  releaseImplementation(libs.chucker.library.no.op)
  debugImplementation(libs.leakcanary.android)
  implementation(libs.androidx.security.crypto)
  implementation(libs.kotlinx.serialization.json)
  implementation(project(":security"))

  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  androidTestImplementation(libs.androidx.benchmark.macro.junit4)
  androidTestImplementation(libs.androidx.uiautomator)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
}

tasks.withType<Test>().configureEach {
  failFast = true
  timeout.set(Duration.ofMinutes(2))
  testLogging { events("passed", "failed", "skipped") }
}


