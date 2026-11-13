package com.institute.security

import android.content.Context
import com.institute.security.authz.AuthzClient
import com.institute.security.device.IntegrityProvider
import com.institute.security.net.SecureHttp
import okhttp3.OkHttpClient

object SecurityKit {
    lateinit var appContext: Context
        private set

    lateinit var config: SecurityConfig
        private set

    /**
     * App integration plug (Section 3.3): returns the active upstream identity token (e.g. Firebase ID token).
     */
    @Volatile
    var identityProvider: (suspend () -> String)? = null

    internal fun init(ctx: Context, cfg: SecurityConfig) {
        appContext = ctx
        config = cfg
    }

    fun isInitialized(): Boolean = ::appContext.isInitialized && ::config.isInitialized

    val http: OkHttpClient by lazy { SecureHttp.build(config) }
    val authz: AuthzClient by lazy { AuthzClient(config, http) }
    val integrity: IntegrityProvider by lazy { IntegrityProvider(appContext, config.cloudProjectNumber) }
}
