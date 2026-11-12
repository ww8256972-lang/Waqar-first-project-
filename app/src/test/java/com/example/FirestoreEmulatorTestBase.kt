package com.example

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.tasks.await
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
abstract class FirestoreEmulatorTestBase {
    protected lateinit var auth: FirebaseAuth
    protected lateinit var db: FirebaseFirestore
    protected lateinit var projectId: String
    protected lateinit var databaseId: String

    companion object {
        private const val HOST = "127.0.0.1"
        private const val AUTH_PORT = 9099
        private const val FIRESTORE_PORT = 8085

        @Volatile private var emulatorsConnected = false

        private fun isEmulatorRunning(): Boolean {
            return try {
                val s = java.net.Socket()
                s.connect(java.net.InetSocketAddress(HOST, FIRESTORE_PORT), 300)
                s.close()
                true
            } catch (_: Exception) {
                false
            }
        }
        @android.annotation.SuppressLint("DiscouragedApi")
        private fun getResourceOrDefault(context: Context, name: String, fallback: String): String {
            val id = context.resources.getIdentifier(name, "string", context.packageName)
            if (id != 0) {
                val v = runCatching { context.getString(id) }.getOrNull()
                if (!v.isNullOrBlank()) return v
            }
            return fallback
        }
    }

    @Before
    fun baseSetUp() {
        ShadowLog.stream = System.out
        org.junit.Assume.assumeTrue(
            "Firebase Local Emulator is not running on $HOST:$FIRESTORE_PORT; skipping emulator tests.",
            isEmulatorRunning()
        )
        val context = ApplicationProvider.getApplicationContext<Context>()

        projectId = getResourceOrDefault(context, "project_id", "gen-lang-client-0309263247")
        databaseId = getResourceOrDefault(
            context,
            "firestore_database_id",
            "ai-studio-android-waqarins-b07db193-f2b2-469a-b782-7cb127ebd6a1"
        )

        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setProjectId(projectId)
                    .setApplicationId(
                        getResourceOrDefault(
                            context,
                            "google_app_id",
                            "1:790218222135:android:ebec440ddaf6637ab4adb2"
                        )
                    )
                    .setApiKey(
                        getResourceOrDefault(
                            context,
                            "google_api_key",
                            "AIzaSyDummyTestApiKeyForLocalEmulator0000"
                        )
                    )
                    .build()
            )
        }

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance(databaseId)

        if (!emulatorsConnected) {
            auth.useEmulator(HOST, AUTH_PORT)
            db.useEmulator(HOST, FIRESTORE_PORT)
            db.firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
                .build()
            emulatorsConnected = true
        }

        clearFirestoreEmulator()
        clearAuthEmulator()
        auth.signOut()
    }

    @After
    fun baseTearDown() {
        runCatching { auth.signOut() }
    }

    protected suspend fun signInTestUser(
        email: String = "alice@waqarinstitute.edu",
        password: String = "testpass123"
    ): String {
        val result = try {
            auth.createUserWithEmailAndPassword(email, password).await()
        } catch (_: Exception) {
            auth.signInWithEmailAndPassword(email, password).await()
        }
        return result.user!!.uid
    }

    private fun clearFirestoreEmulator() {
        for (dbSegment in listOf("(default)", databaseId).distinct()) {
            deleteHttp(
                "http://$HOST:$FIRESTORE_PORT/emulator/v1/projects/$projectId/databases/$dbSegment/documents"
            )
        }
    }

    private fun clearAuthEmulator() {
        deleteHttp("http://$HOST:$AUTH_PORT/emulator/v1/projects/$projectId/accounts")
    }

    private fun deleteHttp(urlString: String) {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            connectTimeout = 5000
            readTimeout = 5000
        }
        try {
            val code = conn.responseCode
            check(code in 200..299) {
                "Failed to clear emulator at $urlString (HTTP $code). Are the Auth ($AUTH_PORT) and Firestore ($FIRESTORE_PORT) emulators running?"
            }
        } catch (e: Exception) {
            Log.e("EmulatorTest", "Emulator cleanup failed for $urlString", e)
            throw e
        } finally {
            conn.disconnect()
        }
    }
}
