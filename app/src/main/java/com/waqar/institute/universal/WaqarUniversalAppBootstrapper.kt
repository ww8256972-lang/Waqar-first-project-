package com.waqar.institute.universal

import android.app.Application
import android.content.Context
import android.util.Log
import com.example.data.firestore.InstituteRepository
import com.example.security.ensureFirebaseInitialized
import com.google.firebase.auth.FirebaseAuth
import com.institute.security.SecurityKit
import com.waqar.institute.utils.GlobalExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Universal OEM-Resilient Application Bootstrapper for Waqar Computer Institute ERP.
 *
 * Designed for 100,000+ student devices across Android 8–15 (Xiaomi, Realme, Vivo, Oppo, Samsung):
 * - Installs GlobalExceptionHandler before any Activity/Service starts.
 * - Ensures FirebaseApp initializes safely (even if default google-services XML was stripped).
 * - Pre-warms named Cloud Firestore instance asynchronously on Dispatchers.IO so Frame 0 renders
 *   immediately without black screen or ANR stalls.
 */
class WaqarUniversalAppBootstrapper : Application() {

    private val bootstrapScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        base?.let { ctx ->
            runCatching { GlobalExceptionHandler.install() }
            runCatching { ensureFirebaseInitialized(ctx) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val appCtx = applicationContext ?: this
        runCatching { GlobalExceptionHandler.install() }
        runCatching { ensureFirebaseInitialized(appCtx) }
        SecurityKit.identityProvider = {
            FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token.orEmpty()
        }

        bootstrapScope.launch {
            runCatching {
                InstituteRepository.getConfiguredFirestore(appCtx)
            }.onFailure { err ->
                Log.w(TAG, "Deferred Firestore warm-up notice: ${err.localizedMessage}")
            }
        }
    }

    private companion object {
        const val TAG = "WciBootstrapper"
    }
}
