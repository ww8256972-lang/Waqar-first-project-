package com.example.data.local

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.security.SecurityPostureManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "wci_preferences")

enum class UiTier(val label: String, val description: String) {
    ULTRA("Ultra (AGSL + 120Hz)", "Real-time shader waves, full glass blur & spring physics"),
    HIGH("High (60fps Glass + Neon)", "Full glass rim, rotating neon borders & smooth springs"),
    STANDARD("Standard (Balanced)", "Translucent surfaces & lightweight animations"),
    REDUCED("Reduced Motion (Battery Saver)", "Static gradients, high contrast, zero continuous loops")
}

data class AppPreferences(
    val darkTheme: Boolean = true,
    val languageCode: String = "EN", // "EN" or "HI"
    val uiTierOverride: String = "AUTO", // "AUTO", "ULTRA", "HIGH", "STANDARD", "REDUCED"
    val reduceMotion: Boolean = false,
    val biometricLockEnabled: Boolean = false,
    val flagSecureEnabled: Boolean = false,
    val selectedPortalRole: String = "admin",
    val instituteName: String = "Waqar Computer Institute",
    val instituteTagline: String = "Excellence in IT, AI & Professional Computing",
    val instituteBranch: String = "Main Campus • ISO 9001:2015 Certified",
    val academicSession: String = "2026–2027",
    val gstNumber: String = "09AABCW1234F1Z5"
)

class PreferencesManager(private val context: Context) {
    companion object {
        private val KEY_DARK_THEME = booleanPreferencesKey("dark_theme")
        private val KEY_LANGUAGE = stringPreferencesKey("language_code")
        private val KEY_UI_TIER = stringPreferencesKey("ui_tier_override")
        private val KEY_REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
        private val KEY_BIOMETRIC_LOCK = booleanPreferencesKey("biometric_lock")
        private val KEY_FLAG_SECURE = booleanPreferencesKey("flag_secure")
        private val KEY_SELECTED_ROLE = stringPreferencesKey("selected_portal_role")
        private val KEY_INSTITUTE_NAME = stringPreferencesKey("institute_name")
        private val KEY_INSTITUTE_TAGLINE = stringPreferencesKey("institute_tagline")
        private val KEY_INSTITUTE_BRANCH = stringPreferencesKey("institute_branch")
        private val KEY_ACADEMIC_SESSION = stringPreferencesKey("academic_session")
        private val KEY_GST_NUMBER = stringPreferencesKey("gst_number")
    }

    val preferencesFlow: Flow<AppPreferences> = context.dataStore.data.map { prefs ->
        AppPreferences(
            darkTheme = prefs[KEY_DARK_THEME] ?: true,
            languageCode = prefs[KEY_LANGUAGE] ?: "EN",
            uiTierOverride = prefs[KEY_UI_TIER] ?: "AUTO",
            reduceMotion = prefs[KEY_REDUCE_MOTION] ?: false,
            biometricLockEnabled = prefs[KEY_BIOMETRIC_LOCK] ?: false,
            flagSecureEnabled = prefs[KEY_FLAG_SECURE] ?: false,
            selectedPortalRole = prefs[KEY_SELECTED_ROLE] ?: "admin",
            instituteName = prefs[KEY_INSTITUTE_NAME] ?: "Waqar Computer Institute",
            instituteTagline = prefs[KEY_INSTITUTE_TAGLINE] ?: "Excellence in IT, AI & Professional Computing",
            instituteBranch = prefs[KEY_INSTITUTE_BRANCH] ?: "Main Campus • ISO 9001:2015 Certified",
            academicSession = prefs[KEY_ACADEMIC_SESSION] ?: "2026–2027",
            gstNumber = prefs[KEY_GST_NUMBER] ?: "09AABCW1234F1Z5"
        )
    }

    fun resolveEffectiveUiTier(prefs: AppPreferences): UiTier {
        if (prefs.reduceMotion || SecurityPostureManager.isBatterySaverActive(context)) {
            return UiTier.REDUCED
        }
        val animScale = runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
        }.getOrDefault(1f)
        if (animScale == 0f) return UiTier.REDUCED

        when (prefs.uiTierOverride) {
            "ULTRA" -> return UiTier.ULTRA
            "HIGH" -> return UiTier.HIGH
            "STANDARD" -> return UiTier.STANDARD
            "REDUCED" -> return UiTier.REDUCED
        }

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val isLowRam = am?.isLowRamDevice == true
        return when {
            isLowRam -> UiTier.STANDARD
            Build.VERSION.SDK_INT >= 33 -> UiTier.ULTRA
            Build.VERSION.SDK_INT >= 31 -> UiTier.HIGH
            else -> UiTier.STANDARD
        }
    }

    suspend fun setDarkTheme(enabled: Boolean) {
        context.dataStore.edit { it[KEY_DARK_THEME] = enabled }
    }

    suspend fun setLanguageCode(lang: String) {
        context.dataStore.edit { it[KEY_LANGUAGE] = lang }
    }

    suspend fun setUiTierOverride(tier: String) {
        context.dataStore.edit { it[KEY_UI_TIER] = tier }
    }

    suspend fun setReduceMotion(reduce: Boolean) {
        context.dataStore.edit { it[KEY_REDUCE_MOTION] = reduce }
    }

    suspend fun setBiometricLock(enabled: Boolean) {
        context.dataStore.edit { it[KEY_BIOMETRIC_LOCK] = enabled }
    }

    suspend fun setFlagSecure(enabled: Boolean) {
        context.dataStore.edit { it[KEY_FLAG_SECURE] = enabled }
    }

    suspend fun setSelectedPortalRole(roleKey: String) {
        context.dataStore.edit { it[KEY_SELECTED_ROLE] = roleKey }
    }

    suspend fun updateInstituteBranding(
        name: String,
        tagline: String,
        branch: String,
        session: String,
        gst: String
    ) {
        context.dataStore.edit {
            it[KEY_INSTITUTE_NAME] = name
            it[KEY_INSTITUTE_TAGLINE] = tagline
            it[KEY_INSTITUTE_BRANCH] = branch
            it[KEY_ACADEMIC_SESSION] = session
            it[KEY_GST_NUMBER] = gst
        }
    }
}
