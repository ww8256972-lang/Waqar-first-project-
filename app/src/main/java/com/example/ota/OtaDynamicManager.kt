package com.example.ota

import android.util.Log
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/**
 * Firebase Remote Config & Dynamic OTA Update Engine.
 *
 * Fetches dynamic UI flags, layout parameters, geofence coordinates, QR rotation intervals,
 * and remote rules on app startup without requiring an APK reinstall.
 */
data class OtaDynamicConfig(
    val qrRefreshIntervalSeconds: Long = 15L,
    val geofenceRadiusMeters: Float = 100.0f,
    val instituteLatitude: Double = 28.6139,
    val instituteLongitude: Double = 77.2090,
    val examQuestionSetSize: Int = 50,
    val dynamicBannerHeadline: String = "Grow Up Computer Skill — ISO 9001:2015 Certified",
    val festivalAutoThemeEnabled: Boolean = true,
    val strictBiometricExamGate: Boolean = true,
    val remoteScriptPayload: String = "card_elevation=14;liquid_wave_speed=1.15;accent_boost=true",
    val lastSyncedEpochMs: Long = 0L
)

object OtaDynamicManager {
    private const val TAG = "OtaDynamicManager"

    private val defaultMap: Map<String, Any> = mapOf(
        "qr_refresh_interval_sec" to 15L,
        "geofence_radius_meters" to 100.0,
        "institute_lat" to 28.6139,
        "institute_lng" to 77.2090,
        "exam_question_set_size" to 50L,
        "dynamic_banner_headline" to "Grow Up Computer Skill — ISO 9001:2015 Certified",
        "festival_auto_theme_enabled" to true,
        "strict_biometric_exam_gate" to true,
        "remote_script_payload" to "card_elevation=14;liquid_wave_speed=1.15;accent_boost=true"
    )

    private val _configFlow = MutableStateFlow(OtaDynamicConfig())
    val configFlow: StateFlow<OtaDynamicConfig> = _configFlow.asStateFlow()

    suspend fun initializeAndFetchOtaConfig(): OtaDynamicConfig {
        return runCatching {
            val remoteConfig = FirebaseRemoteConfig.getInstance()
            val settings = FirebaseRemoteConfigSettings.Builder()
                .setMinimumFetchIntervalInSeconds(300L)
                .build()
            remoteConfig.setConfigSettingsAsync(settings).await()
            remoteConfig.setDefaultsAsync(defaultMap).await()
            runCatching { remoteConfig.fetchAndActivate().await() }

            val updated = OtaDynamicConfig(
                qrRefreshIntervalSeconds = remoteConfig.getLong("qr_refresh_interval_sec").coerceIn(10L, 60L),
                geofenceRadiusMeters = remoteConfig.getDouble("geofence_radius_meters").toFloat().coerceIn(25f, 1000f),
                instituteLatitude = remoteConfig.getDouble("institute_lat"),
                instituteLongitude = remoteConfig.getDouble("institute_lng"),
                examQuestionSetSize = remoteConfig.getLong("exam_question_set_size").toInt().coerceIn(10, 100),
                dynamicBannerHeadline = remoteConfig.getString("dynamic_banner_headline")
                    .ifBlank { "Grow Up Computer Skill — ISO 9001:2015 Certified" },
                festivalAutoThemeEnabled = remoteConfig.getBoolean("festival_auto_theme_enabled"),
                strictBiometricExamGate = remoteConfig.getBoolean("strict_biometric_exam_gate"),
                remoteScriptPayload = remoteConfig.getString("remote_script_payload")
                    .ifBlank { "card_elevation=14;liquid_wave_speed=1.15;accent_boost=true" },
                lastSyncedEpochMs = System.currentTimeMillis()
            )
            _configFlow.value = updated
            updated
        }.getOrElse { e ->
            Log.d(TAG, "Using built-in OTA defaults: ${e.localizedMessage}")
            val fallback = OtaDynamicConfig(lastSyncedEpochMs = System.currentTimeMillis())
            _configFlow.value = fallback
            fallback
        }
    }

    /**
     * Pinned OkHttpClient with SPKI SHA-256 fingerprinting & Certificate Transparency
     * for tamper-proof OTA update verification.
     */
    fun createPinnedOtaClient(context: android.content.Context): okhttp3.OkHttpClient {
        return com.example.network.CertificatePinnedHttpClient.buildPinnedOkHttpClient(context)
    }
}
