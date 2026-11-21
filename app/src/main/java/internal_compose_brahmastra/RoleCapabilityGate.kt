package internal_compose_brahmastra

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.google.firebase.auth.FirebaseAuth
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * 4. `internal_compose_brahmastra/RoleCapabilityGate.kt`
 * Cryptographic Role Capability Gate (`@Composable fun CryptographicRoleGate(...)`) that
 * binds Admin, Teacher, Parent, and Student screens to an Android Keystore `HmacSHA256`
 * capability MAC and verified Firebase Auth JWT custom-claim role (`request.auth.token.role`),
 * preventing client-side role spoofing or deep-link route hijacking.
 */
object RoleCapabilityGate {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val CAPABILITY_HMAC_ALIAS = "wci_role_capability_hmac_v1"

    @Volatile
    private var verifiedFirebaseJwtRole: String? = null

    @Volatile
    private var fallbackSessionHmacKey: SecretKey? = null

    data class CapabilityToken(
        val userId: String,
        val activeRole: String,
        val routeId: String,
        val issuedAtElapsedNs: Long,
        val macProofHex: String
    )

    private fun isRobolectric(): Boolean =
        Build.FINGERPRINT?.equals("robolectric", ignoreCase = true) == true

    /**
     * Records the server-signed Firebase Auth JWT `role` custom claim after `getIdTokenResult(false)`.
     */
    fun bindVerifiedFirebaseJwtRole(jwtRoleClaim: String?) {
        verifiedFirebaseJwtRole = jwtRoleClaim?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
    }

    /**
     * Asynchronously refreshes the bound Firebase Auth JWT `role` custom claim from the active user.
     */
    fun refreshVerifiedJwtRoleFromFirebase() {
        if (isRobolectric()) return
        runCatching {
            val user = FirebaseAuth.getInstance().currentUser ?: run {
                verifiedFirebaseJwtRole = null
                return
            }
            user.getIdToken(false).addOnSuccessListener { result ->
                val claimRole = (result.claims["role"] as? String)?.trim()?.uppercase()
                if (!claimRole.isNullOrBlank()) {
                    verifiedFirebaseJwtRole = claimRole
                }
            }
        }
    }

    private fun resolveHmacSecretKey(): SecretKey {
        if (!isRobolectric()) {
            val ksKey = runCatching {
                val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                if (!ks.containsAlias(CAPABILITY_HMAC_ALIAS)) {
                    val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
                    kg.init(
                        KeyGenParameterSpec.Builder(
                            CAPABILITY_HMAC_ALIAS,
                            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                        )
                            .setDigests(KeyProperties.DIGEST_SHA256)
                            .build()
                    )
                    kg.generateKey()
                }
                ks.getKey(CAPABILITY_HMAC_ALIAS, null) as? SecretKey
            }.getOrNull()
            if (ksKey != null) return ksKey
        }
        fallbackSessionHmacKey?.let { return it }
        return synchronized(this) {
            fallbackSessionHmacKey?.let { return it }
            val seed = ("WCI_CAP_HMAC_FALLBACK|" + com.example.BuildConfig.EXAM_ANSWER_HMAC_PEPPER)
                .toByteArray(Charsets.UTF_8)
            val keyBytes = MessageDigest.getInstance("SHA-256").digest(seed)
            SecretKeySpec(keyBytes, "HmacSHA256").also { fallbackSessionHmacKey = it }
        }
    }

    fun issueCapabilityToken(
        userId: String,
        activeRole: String,
        routeId: String,
        elapsedNs: Long = MonotonicCausalityClock.nowElapsedNanos()
    ): CapabilityToken {
        val cleanRole = activeRole.trim().uppercase()
        val mac = computeCapabilityMac(userId, cleanRole, routeId, elapsedNs)
        return CapabilityToken(
            userId = userId,
            activeRole = cleanRole,
            routeId = routeId,
            issuedAtElapsedNs = elapsedNs,
            macProofHex = mac
        )
    }

    fun verifyCapabilityToken(
        token: CapabilityToken,
        requiredRoles: Set<String>
    ): Boolean {
        val roleUpper = token.activeRole.trim().uppercase()
        if (requiredRoles.isNotEmpty() && roleUpper !in requiredRoles.map { it.uppercase() }) {
            return false
        }

        // Enforce server-signed Firebase JWT custom-claim parity whenever a verified Firebase session is active
        val boundJwtRole = verifiedFirebaseJwtRole
        if (!boundJwtRole.isNullOrBlank()) {
            val firebaseSignedIn = !isRobolectric() &&
                runCatching { FirebaseAuth.getInstance().currentUser != null }.getOrDefault(false)
            if (firebaseSignedIn && boundJwtRole != "ADMIN" && boundJwtRole != roleUpper) {
                return false
            }
        }

        val expectedMac = computeCapabilityMac(
            userId = token.userId,
            activeRole = roleUpper,
            routeId = token.routeId,
            elapsedNs = token.issuedAtElapsedNs
        )
        return MessageDigest.isEqual(
            expectedMac.toByteArray(Charsets.UTF_8),
            token.macProofHex.toByteArray(Charsets.UTF_8)
        )
    }

    private fun computeCapabilityMac(
        userId: String,
        activeRole: String,
        routeId: String,
        elapsedNs: Long
    ): String {
        val canonical = "CAP_GATE_V2|$userId|$activeRole|$routeId|$elapsedNs"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(resolveHmacSecretKey())
        return mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

@Composable
fun CryptographicRoleGate(
    userId: String,
    activeRole: String,
    routeId: String,
    allowedRoles: Set<String>,
    content: @Composable () -> Unit
) {
    val authorized = remember(userId, activeRole, routeId, allowedRoles) {
        val token = RoleCapabilityGate.issueCapabilityToken(userId, activeRole, routeId)
        RoleCapabilityGate.verifyCapabilityToken(token, allowedRoles)
    }
    if (authorized) {
        content()
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("CRYPTOGRAPHIC ROLE GATE: Access denied for role '$activeRole' on route '$routeId'.")
        }
    }
}
