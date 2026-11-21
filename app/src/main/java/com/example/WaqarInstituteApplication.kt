package com.example

import android.app.Application
import android.content.Context
import com.example.security.ensureFirebaseInitialized
import com.waqar.institute.utils.GlobalExceptionHandler

/**
 * Legacy Application class kept solely for backward source compatibility.
 *
 * The active Application declared in `AndroidManifest.xml` is
 * [com.waqar.institute.universal.WaqarUniversalAppBootstrapper].
 */
@Deprecated(
    message = "Use com.waqar.institute.universal.WaqarUniversalAppBootstrapper declared in AndroidManifest.xml.",
    replaceWith = ReplaceWith("com.waqar.institute.universal.WaqarUniversalAppBootstrapper")
)
class WaqarInstituteApplication : Application() {

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        base?.let { ctx ->
            runCatching { GlobalExceptionHandler.install() }
            runCatching { ensureFirebaseInitialized(ctx) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        runCatching { GlobalExceptionHandler.install() }
        runCatching { ensureFirebaseInitialized(this) }
    }
}
