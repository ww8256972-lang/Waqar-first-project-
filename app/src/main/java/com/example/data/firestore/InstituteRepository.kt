package com.example.data.firestore

import android.content.Context
import android.util.Log
import com.example.data.model.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class InstituteRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    fun firestoreDb(): FirebaseFirestore = db

    companion object {
        const val BOOTSTRAPPED_ADMIN_EMAIL = "samdkhan8256972@gmail.com"
        private const val LOCAL_VAULT_PREFS = "wci_local_erp_vault_prefs"

        @Volatile
        private var appContext: Context? = null

        val localCredentialsCache = java.util.concurrent.ConcurrentHashMap<String, PortalCredential>()
        val localStudentsCache = java.util.concurrent.ConcurrentHashMap<String, StudentRecord>()
        val localStaffCache = java.util.concurrent.ConcurrentHashMap<String, StaffMember>()
        val localGuardiansCache = java.util.concurrent.ConcurrentHashMap<String, GuardianLink>()

        private const val DEFAULT_FIRESTORE_DATABASE_ID =
            "ai-studio-android-waqarins-b07db193-f2b2-469a-b782-7cb127ebd6a1"

        @android.annotation.SuppressLint("DiscouragedApi")
        fun resolveFirestoreDatabaseId(context: Context): String {
            val resId = context.resources.getIdentifier(
                "firestore_database_id",
                "string",
                context.packageName
            )
            if (resId != 0) {
                val value = runCatching { context.getString(resId) }.getOrNull()
                if (!value.isNullOrBlank()) return value
            }
            return DEFAULT_FIRESTORE_DATABASE_ID
        }

        fun getConfiguredFirestore(context: Context? = null): FirebaseFirestore {
            val ctx = context?.applicationContext ?: appContext
            if (ctx != null) {
                com.example.security.ensureFirebaseInitialized(ctx)
                val dbId = resolveFirestoreDatabaseId(ctx)
                return FirebaseFirestore.getInstance(dbId)
            }
            return runCatching {
                FirebaseFirestore.getInstance(DEFAULT_FIRESTORE_DATABASE_ID)
            }.getOrElse {
                FirebaseFirestore.getInstance()
            }
        }

        fun create(context: Context): InstituteRepository {
            appContext = context.applicationContext
            com.example.security.ensureFirebaseInitialized(context)
            loadLocalVaultFromPrefs(context.applicationContext)
            val databaseId = resolveFirestoreDatabaseId(context)
            val firestore = FirebaseFirestore.getInstance(databaseId)
            try {
                val currentSettings = firestore.firestoreSettings
                val cacheSettings = PersistentCacheSettings.newBuilder()
                    .setSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                    .build()
                val updatedSettings = FirebaseFirestoreSettings.Builder(currentSettings)
                    .setLocalCacheSettings(cacheSettings)
                    .build()
                firestore.firestoreSettings = updatedSettings
            } catch (e: Exception) {
                Log.w("InstituteRepository", "Firestore persistence settings already initialized: ${e.message}")
            }
            return InstituteRepository(firestore, FirebaseAuth.getInstance())
        }

        private fun openSecureVaultPrefs(context: Context): android.content.SharedPreferences =
            com.example.security.HardwareKeystoreVault.openEncryptedPreferences(context)

        fun cacheCredentialLocally(credential: PortalCredential) {
            val key = credential.username.trim().lowercase()
            if (key.isBlank()) return
            val roles = credential.effectivePortalRoles()
            val normalized = credential.copy(
                username = key,
                isStudent = credential.isStudent || "student" in roles,
                isTeacher = credential.isTeacher || "teacher" in roles,
                isParent = credential.isParent || "parent" in roles,
                isAdmin = credential.isAdmin || "admin" in roles,
                portalRoles = roles
            )
            localCredentialsCache[key] = normalized
            val ctx = appContext ?: return
            runCatching {
                val prefs = openSecureVaultPrefs(ctx)
                val rolesArr = org.json.JSONArray(normalized.portalRoles)
                val obj = org.json.JSONObject().apply {
                    put("username", normalized.username)
                    put("passwordHash", normalized.passwordHash)
                    put("passwordSalt", normalized.passwordSalt)
                    put("role", normalized.role)
                    put("linkedEntityId", normalized.linkedEntityId)
                    put("displayName", normalized.displayName)
                    put("assignedByUid", normalized.assignedByUid)
                    put("active", normalized.active)
                    put("isStudent", normalized.isStudent)
                    put("isTeacher", normalized.isTeacher)
                    put("isParent", normalized.isParent)
                    put("isAdmin", normalized.isAdmin)
                    put("portalRoles", rolesArr)
                }
                prefs.edit().putString("cred_$key", obj.toString()).apply()
            }
        }

        private fun parsePortalCredentialFromJson(key: String, obj: org.json.JSONObject): PortalCredential {
            val role = obj.optString("role", "student")
            val rolesJson = obj.optJSONArray("portalRoles")
            val parsedRoles = buildList {
                if (rolesJson != null) {
                    for (i in 0 until rolesJson.length()) {
                        val r = rolesJson.optString(i, "").trim().lowercase()
                        if (r.isNotBlank()) add(r)
                    }
                }
                if (isEmpty() && role.isNotBlank()) add(role.lowercase())
            }.distinct()
            return PortalCredential(
                username = obj.optString("username", key),
                passwordHash = obj.optString("passwordHash", ""),
                passwordSalt = obj.optString("passwordSalt", ""),
                role = role,
                linkedEntityId = obj.optString("linkedEntityId", ""),
                displayName = obj.optString("displayName", key),
                assignedByUid = obj.optString("assignedByUid", "admin_waqar_root"),
                active = obj.optBoolean("active", true),
                isStudent = obj.optBoolean("isStudent", "student" in parsedRoles),
                isTeacher = obj.optBoolean("isTeacher", "teacher" in parsedRoles),
                isParent = obj.optBoolean("isParent", "parent" in parsedRoles),
                isAdmin = obj.optBoolean("isAdmin", "admin" in parsedRoles),
                portalRoles = parsedRoles
            )
        }

        fun getCachedCredentialLocally(username: String): PortalCredential? {
            val rawTrimmed = username.trim()
            val key = rawTrimmed.lowercase()
            if (key.isBlank()) return null
            localCredentialsCache[key]?.let { return it }

            // Also resolve if the user typed their Roll No / Enrollment No, Employee Code, or Linked Entity ID
            val matchedStudent = localStudentsCache.values.firstOrNull {
                it.enrollmentNo.equals(rawTrimmed, ignoreCase = true) ||
                    it.id.equals(rawTrimmed, ignoreCase = true)
            }
            val matchedStaff = localStaffCache.values.firstOrNull {
                it.employeeCode.equals(rawTrimmed, ignoreCase = true) ||
                    it.teacherUsername.equals(rawTrimmed, ignoreCase = true) ||
                    it.id.equals(rawTrimmed, ignoreCase = true)
            }
            val matchedGuardian = localGuardiansCache.values.firstOrNull {
                it.phone.equals(rawTrimmed, ignoreCase = true) ||
                    it.id.equals(rawTrimmed, ignoreCase = true)
            }
            localCredentialsCache.values.firstOrNull { cred ->
                cred.username.equals(key, ignoreCase = true) ||
                    cred.linkedEntityId.equals(rawTrimmed, ignoreCase = true) ||
                    (matchedStudent != null && (
                        cred.linkedEntityId.equals(matchedStudent.id, ignoreCase = true) ||
                            cred.displayName.equals(matchedStudent.fullName, ignoreCase = true)
                        )) ||
                    (matchedStaff != null && (
                        cred.linkedEntityId.equals(matchedStaff.id, ignoreCase = true) ||
                            cred.username.equals(matchedStaff.teacherUsername, ignoreCase = true)
                        )) ||
                    (matchedGuardian != null && cred.linkedEntityId.equals(matchedGuardian.id, ignoreCase = true))
            }?.let { return it }

            val ctx = appContext ?: return null
            return runCatching {
                val prefs = openSecureVaultPrefs(ctx)
                val raw = prefs.getString("cred_$key", null)
                    ?: ctx.getSharedPreferences(LOCAL_VAULT_PREFS, Context.MODE_PRIVATE).getString("cred_$key", null)
                    ?: return null
                val obj = org.json.JSONObject(raw)
                parsePortalCredentialFromJson(key, obj).also { localCredentialsCache[key] = it }
            }.getOrNull()
        }

        private fun loadLocalVaultFromPrefs(context: Context) {
            runCatching {
                val securePrefs = openSecureVaultPrefs(context)
                val legacyPrefs = context.getSharedPreferences(LOCAL_VAULT_PREFS, Context.MODE_PRIVATE)
                val legacyAll = legacyPrefs.all
                if (legacyAll.isNotEmpty()) {
                    val secureEdit = securePrefs.edit()
                    val legacyEdit = legacyPrefs.edit()
                    legacyAll.forEach { (k, v) ->
                        val raw = v as? String ?: return@forEach
                        if (k.startsWith("cred_")) {
                            secureEdit.putString(k, raw)
                            legacyEdit.remove(k)
                        }
                    }
                    secureEdit.apply()
                    legacyEdit.apply()
                }
                securePrefs.all.forEach { (k, v) ->
                    val raw = v as? String ?: return@forEach
                    if (k.startsWith("cred_")) {
                        val obj = org.json.JSONObject(raw)
                        val uname = obj.optString("username", k.removePrefix("cred_")).lowercase()
                        localCredentialsCache[uname] = parsePortalCredentialFromJson(uname, obj)
                    }
                }
            }
        }

        fun isOfflineOrNetworkError(throwable: Throwable): Boolean {
            var curr: Throwable? = throwable
            while (curr != null) {
                if (curr is java.io.IOException) return true
                if (curr is kotlinx.coroutines.TimeoutCancellationException) return true
                if (curr is FirebaseFirestoreException) {
                    if (curr.code == FirebaseFirestoreException.Code.UNAVAILABLE ||
                        curr.code == FirebaseFirestoreException.Code.DEADLINE_EXCEEDED ||
                        curr.message?.contains("offline", ignoreCase = true) == true ||
                        curr.message?.contains("network", ignoreCase = true) == true ||
                        curr.message?.contains("deadline exceeded", ignoreCase = true) == true
                    ) {
                        return true
                    }
                }
                val msg = curr.message
                if (msg != null && (
                    msg.contains("offline", ignoreCase = true) ||
                    msg.contains("network", ignoreCase = true) ||
                    msg.contains("client is offline", ignoreCase = true) ||
                    msg.contains("unable to resolve host", ignoreCase = true) ||
                    msg.contains("connection refused", ignoreCase = true)
                )) {
                    return true
                }
                curr = curr.cause
            }
            return false
        }
    }

    // =========================================================================
    // M01: User Profile, Username Alias & Device Sessions
    // =========================================================================

    suspend fun saveUserProfile(profile: UserProfile) {
        val path = "users/${profile.uid}"
        try {
            db.collection("users").document(profile.uid).set(profile.toMap()).await()
        } catch (e: Exception) {
            if (!isOfflineOrNetworkError(e)) {
                handleFirestoreError(e, OperationType.WRITE, path, auth)
            }
        }
    }

    suspend fun getUserProfile(uid: String): UserProfile? {
        val path = "users/$uid"
        val docRef = db.collection("users").document(uid)
        val serverSnap = try {
            kotlinx.coroutines.withTimeoutOrNull(6_000L) {
                docRef.get(Source.SERVER).await()
            }
        } catch (e: Exception) {
            if (!isOfflineOrNetworkError(e)) {
                handleFirestoreError(e, OperationType.GET, path, auth)
            }
            null
        }
        if (serverSnap != null) {
            return if (serverSnap.exists()) serverSnap.toObject(UserProfile::class.java) else null
        }
        return try {
            val cachedSnap = docRef.get(Source.CACHE).await()
            if (cachedSnap.exists()) cachedSnap.toObject(UserProfile::class.java) else null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun reserveUsername(alias: UsernameAlias) {
        val cleanKey = alias.username.trim().lowercase()
        val path = "usernames/$cleanKey"
        try {
            db.collection("usernames").document(cleanKey)
                .set(alias.copy(username = cleanKey).toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    suspend fun saveDeviceSession(session: DeviceSession) {
        val path = "sessions/${session.sessionId}"
        try {
            db.collection("sessions").document(session.sessionId).set(session.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    suspend fun saveBoundPortalSession(
        uid: String,
        actorId: String,
        username: String,
        role: String,
        deviceFingerprint: String,
        expiresAt: com.google.firebase.Timestamp
    ) {
        val path = "sessions/$uid"
        val now = com.google.firebase.Timestamp(java.util.Date(System.currentTimeMillis() - 1000))
        val payload = mapOf(
            "sessionId" to uid,
            "uid" to uid,
            "userId" to uid,
            "actorId" to actorId.ifBlank { uid },
            "username" to username,
            "role" to role.lowercase(),
            "deviceName" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            "deviceModel" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            "platform" to "Android ${android.os.Build.VERSION.RELEASE}",
            "activeDeviceToken" to deviceFingerprint.ifBlank { uid },
            "active" to true,
            "expiresAt" to expiresAt,
            "createdAt" to now,
            "updatedAt" to now
        )
        try {
            db.collection("sessions").document(uid).set(payload).await()
        } catch (e: Exception) {
            if (!isOfflineOrNetworkError(e)) {
                handleFirestoreError(e, OperationType.WRITE, path, auth)
            }
        }
    }

    suspend fun revokeDeviceSession(session: DeviceSession) {
        val path = "sessions/${session.sessionId}"
        try {
            db.collection("sessions").document(session.sessionId)
                .set(session.copy(active = false).toMap()).await()
        } catch (e: Exception) {
            if (!isOfflineOrNetworkError(e)) {
                handleFirestoreError(e, OperationType.UPDATE, path, auth)
            }
        }
    }

    fun observeSessions(uid: String): Flow<List<DeviceSession>> = callbackFlow {
        val reg = db.collection("sessions")
            .whereEqualTo("uid", uid)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    close(err)
                    return@addSnapshotListener
                }
                val list = snap?.documents?.mapNotNull { it.toObject(DeviceSession::class.java) }
                    ?.sortedByDescending { it.createdAt } ?: emptyList()
                trySend(list)
            }
        awaitClose { reg.remove() }
    }

    suspend fun getPortalCredential(username: String): PortalCredential? {
        val cleanKey = username.trim().lowercase()
        // 1. Instant local/encrypted vault lookup (<5ms)
        getCachedCredentialLocally(cleanKey)?.let { return it }

        val docRef = db.collection("portalCredentials").document(cleanKey)

        // 2. Check Firestore persistent offline cache (<15ms)
        try {
            val cachedSnap = docRef.get(Source.CACHE).await()
            if (cachedSnap.exists()) {
                val obj = cachedSnap.toObject(PortalCredential::class.java)
                if (obj != null) {
                    cacheCredentialLocally(obj)
                    return obj
                }
            }
        } catch (_: Exception) {
        }

        // 3. Fast server lookup with 1.5s timeout for cross-device accounts
        val serverSnap = try {
            kotlinx.coroutines.withTimeoutOrNull(1_500L) {
                docRef.get(Source.SERVER).await()
            }
        } catch (_: Exception) {
            null
        }
        if (serverSnap != null && serverSnap.exists()) {
            val obj = serverSnap.toObject(PortalCredential::class.java)
            if (obj != null) {
                cacheCredentialLocally(obj)
                return obj
            }
        }

        return getCachedCredentialLocally(cleanKey)
    }

    suspend fun savePortalCredential(credential: PortalCredential) {
        savePortalCredentialWithConfirmation(credential).getOrThrow()
    }

    suspend fun savePortalCredentialWithConfirmation(
        credential: PortalCredential,
        timeoutMs: Long = 2_500L
    ): Result<PortalCredential> = runCatching {
        val cleanKey = credential.username.trim().lowercase()
        val roles = credential.effectivePortalRoles()
        val normalized = credential.copy(
            username = cleanKey,
            isStudent = credential.isStudent || "student" in roles,
            isTeacher = credential.isTeacher || "teacher" in roles,
            isParent = credential.isParent || "parent" in roles,
            isAdmin = credential.isAdmin || "admin" in roles,
            portalRoles = roles
        )
        // Cache immediately in memory + encrypted SharedPreferences so login works in <5ms
        cacheCredentialLocally(normalized)
        val path = "portalCredentials/$cleanKey"
        try {
            val writeTask = db.collection("portalCredentials").document(cleanKey).set(normalized.toMap())
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs.coerceAtMost(2_500L)) {
                writeTask.await()
            }
            normalized
        } catch (e: Exception) {
            Log.w("InstituteRepository", "Deferred cloud sync for portalCredential at $path: ${e.message}")
            normalized
        }
    }

    suspend fun deletePortalCredential(username: String) {
        val cleanKey = username.trim().lowercase()
        localCredentialsCache.remove(cleanKey)
        appContext?.let { ctx ->
            runCatching {
                openSecureVaultPrefs(ctx).edit().remove("cred_$cleanKey").apply()
                ctx.getSharedPreferences(LOCAL_VAULT_PREFS, Context.MODE_PRIVATE)
                    .edit().remove("cred_$cleanKey").apply()
            }
        }
        val path = "portalCredentials/$cleanKey"
        try {
            db.collection("portalCredentials").document(cleanKey).delete().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path, auth)
        }
    }

    fun observePortalCredentials(): Flow<List<PortalCredential>> =
        observeCollection("portalCredentials", PortalCredential::class.java)

    // =========================================================================
    // M03: Student Management
    // =========================================================================

    fun observeStudents(): Flow<List<StudentRecord>> = observeCollection("students", StudentRecord::class.java)

    suspend fun saveStudent(student: StudentRecord) {
        saveStudentWithConfirmation(student).getOrElse { e ->
            if (e is FirestoreOperationException) throw e
            if (!isOfflineOrNetworkError(e)) {
                handleFirestoreError(e, OperationType.WRITE, "students/${student.id}", auth)
            }
        }
    }

    suspend fun saveStudentWithConfirmation(
        student: StudentRecord,
        timeoutMs: Long = 2_500L
    ): Result<Unit> = runCatching {
        // Update local cache immediately before cloud sync
        localStudentsCache[student.id] = student
        val path = "students/${student.id}"
        try {
            val writeTask = db.collection("students").document(student.id).set(student.toMap())
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs.coerceAtMost(2_500L)) {
                writeTask.await()
            }
        } catch (e: Exception) {
            Log.w("InstituteRepository", "Deferred cloud sync for student at $path: ${e.message}")
        }
    }

    suspend fun deleteStudent(studentId: String) {
        localStudentsCache.remove(studentId)
        val path = "students/$studentId"
        try {
            db.collection("students").document(studentId).delete().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path, auth)
        }
    }

    suspend fun findMatchingStudentForLogin(rawUsername: String): StudentRecord? {
        val clean = rawUsername.trim().lowercase()
        val slug = clean.replace(Regex("[^a-z0-9]"), "")
        fun matches(s: StudentRecord): Boolean {
            val stuNameSlug = s.fullName.trim().lowercase().replace(Regex("[^a-z0-9]"), "")
            val guardSlug = s.guardianName.trim().lowercase().replace(Regex("[^a-z0-9]"), "")
            val enrollLower = s.enrollmentNo.trim().lowercase()
            val emailUser = s.email.substringBefore("@").trim().lowercase()
            return clean == stuNameSlug ||
                clean == "${stuNameSlug}_wci" ||
                slug == stuNameSlug ||
                slug == "${stuNameSlug}wci" ||
                clean == "parent_$stuNameSlug" ||
                slug == "parent$stuNameSlug" ||
                (guardSlug.isNotBlank() && (clean == "parent_$guardSlug" || slug == "parent$guardSlug" || clean == guardSlug)) ||
                clean == enrollLower ||
                slug == enrollLower.replace(Regex("[^a-z0-9]"), "") ||
                clean == s.phone.trim() ||
                (s.guardianPhone.isNotBlank() && clean == s.guardianPhone.trim()) ||
                (emailUser.isNotBlank() && clean == emailUser)
        }

        return runCatching {
            val serverSnap = runCatching {
                kotlinx.coroutines.withTimeoutOrNull(6_000L) {
                    db.collection("students").limit(250).get(Source.SERVER).await()
                }
            }.getOrNull()
            val serverList = serverSnap?.documents?.mapNotNull { it.toObject(StudentRecord::class.java) } ?: emptyList()
            serverList.forEach { localStudentsCache[it.id] = it }
            serverList.firstOrNull(::matches)?.let { return@runCatching it }

            localStudentsCache.values.firstOrNull(::matches)?.let { return@runCatching it }

            val cachedList = runCatching {
                db.collection("students").limit(250).get(Source.CACHE).await()
                    .documents.mapNotNull { it.toObject(StudentRecord::class.java) }
            }.getOrDefault(emptyList())
            cachedList.forEach { localStudentsCache[it.id] = it }
            cachedList.firstOrNull(::matches)
        }.getOrNull()
    }

    /**
     * Cursor-based pagination for Cloud Firestore `students` collection (Part 7 Scalability).
     */
    suspend fun fetchStudentsPage(
        limit: Long = 100L,
        lastDocument: com.google.firebase.firestore.DocumentSnapshot? = null
    ): Pair<List<StudentRecord>, com.google.firebase.firestore.DocumentSnapshot?> {
        var query = db.collection("students")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(limit.coerceIn(1L, 500L))
        if (lastDocument != null) {
            query = query.startAfter(lastDocument)
        }
        val snap = query.get().await()
        val items = snap.documents.mapNotNull { it.toObject(StudentRecord::class.java) }
        val nextCursor = snap.documents.lastOrNull()
        return items to nextCursor
    }

    suspend fun findMatchingStaffForLogin(rawUsername: String): StaffMember? {
        val clean = rawUsername.trim().lowercase()
        val slug = clean.replace(Regex("[^a-z0-9]"), "")
        fun matches(st: StaffMember): Boolean {
            val nameSlug = st.fullName.trim().lowercase().replace(Regex("[^a-z0-9]"), "")
            val tUser = st.teacherUsername.trim().lowercase()
            val empLower = st.employeeCode.trim().lowercase()
            val emailUser = st.email.substringBefore("@").trim().lowercase()
            return (tUser.isNotBlank() && clean == tUser) ||
                clean == nameSlug ||
                clean == "faculty_$nameSlug" ||
                clean == "${nameSlug}_wci" ||
                slug == nameSlug ||
                slug == "faculty$nameSlug" ||
                clean == empLower ||
                clean == st.phone.trim() ||
                (emailUser.isNotBlank() && clean == emailUser)
        }

        return runCatching {
            val serverSnap = runCatching {
                kotlinx.coroutines.withTimeoutOrNull(6_000L) {
                    db.collection("staff").get(Source.SERVER).await()
                }
            }.getOrNull()
            val serverList = serverSnap?.documents?.mapNotNull { it.toObject(StaffMember::class.java) } ?: emptyList()
            serverList.forEach { localStaffCache[it.id] = it }
            serverList.firstOrNull(::matches)?.let { return@runCatching it }

            localStaffCache.values.firstOrNull(::matches)?.let { return@runCatching it }

            val cachedList = runCatching {
                db.collection("staff").get(Source.CACHE).await()
                    .documents.mapNotNull { it.toObject(StaffMember::class.java) }
            }.getOrDefault(emptyList())
            cachedList.forEach { localStaffCache[it.id] = it }
            cachedList.firstOrNull(::matches)
        }.getOrNull()
    }

    suspend fun findMatchingGuardianForLogin(rawUsername: String): GuardianLink? {
        val clean = rawUsername.trim().lowercase()
        val slug = clean.replace(Regex("[^a-z0-9]"), "")
        fun matches(g: GuardianLink): Boolean {
            val gSlug = g.guardianName.trim().lowercase().replace(Regex("[^a-z0-9]"), "")
            val sSlug = g.studentName.trim().lowercase().replace(Regex("[^a-z0-9]"), "")
            val pUser = g.parentUsername.trim().lowercase()
            return (pUser.isNotBlank() && clean == pUser) ||
                (gSlug.isNotBlank() && (clean == "parent_$gSlug" || slug == "parent$gSlug" || clean == gSlug)) ||
                (sSlug.isNotBlank() && (clean == "parent_$sSlug" || slug == "parent$sSlug")) ||
                clean == g.phone.trim()
        }

        return runCatching {
            val serverSnap = runCatching {
                kotlinx.coroutines.withTimeoutOrNull(6_000L) {
                    db.collection("guardians").get(Source.SERVER).await()
                }
            }.getOrNull()
            val serverList = serverSnap?.documents?.mapNotNull { it.toObject(GuardianLink::class.java) } ?: emptyList()
            serverList.forEach { localGuardiansCache[it.id] = it }
            serverList.firstOrNull(::matches)?.let { return@runCatching it }

            localGuardiansCache.values.firstOrNull(::matches)?.let { return@runCatching it }

            val cachedList = runCatching {
                db.collection("guardians").get(Source.CACHE).await()
                    .documents.mapNotNull { it.toObject(GuardianLink::class.java) }
            }.getOrDefault(emptyList())
            cachedList.forEach { localGuardiansCache[it.id] = it }
            cachedList.firstOrNull(::matches)
        }.getOrNull()
    }

    // =========================================================================
    // M04: Admission CRM & Enquiries
    // =========================================================================

    fun observeEnquiries(): Flow<List<AdmissionEnquiry>> = observeCollection("enquiries", AdmissionEnquiry::class.java)

    suspend fun saveEnquiry(enquiry: AdmissionEnquiry) {
        val path = "enquiries/${enquiry.id}"
        try {
            db.collection("enquiries").document(enquiry.id).set(enquiry.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    suspend fun deleteEnquiry(enquiryId: String) {
        val path = "enquiries/$enquiryId"
        try {
            db.collection("enquiries").document(enquiryId).delete().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path, auth)
        }
    }

    // =========================================================================
    // M05: Courses & Curriculum
    // =========================================================================

    fun observeCourses(): Flow<List<CourseRecord>> = observeCollection("courses", CourseRecord::class.java)

    suspend fun saveCourse(course: CourseRecord) {
        val path = "courses/${course.id}"
        try {
            db.collection("courses").document(course.id).set(course.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    suspend fun deleteCourse(courseId: String) {
        val path = "courses/$courseId"
        try {
            db.collection("courses").document(courseId).delete().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path, auth)
        }
    }

    // =========================================================================
    // M06: Batches & Timetable
    // =========================================================================

    fun observeBatches(): Flow<List<BatchRecord>> = observeCollection("batches", BatchRecord::class.java)

    suspend fun saveBatch(batch: BatchRecord) {
        val path = "batches/${batch.id}"
        try {
            db.collection("batches").document(batch.id).set(batch.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    suspend fun deleteBatch(batchId: String) {
        val path = "batches/$batchId"
        try {
            db.collection("batches").document(batchId).delete().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path, auth)
        }
    }

    // =========================================================================
    // M07: Attendance (Student & Staff + QR)
    // =========================================================================

    fun observeAttendance(): Flow<List<AttendanceRecord>> = observeCollection("attendance", AttendanceRecord::class.java)

    suspend fun saveAttendance(record: AttendanceRecord) {
        val path = "attendance/${record.id}"
        try {
            db.collection("attendance").document(record.id).set(record.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    // =========================================================================
    // M08: Fees, Invoices & Payments
    // =========================================================================

    fun observeFees(): Flow<List<FeeInvoice>> = observeCollection("fees", FeeInvoice::class.java)

    suspend fun saveFeeInvoice(invoice: FeeInvoice) {
        val path = "fees/${invoice.id}"
        try {
            db.collection("fees").document(invoice.id).set(invoice.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    suspend fun deleteFeeInvoice(feeId: String) {
        val path = "fees/$feeId"
        try {
            db.collection("fees").document(feeId).delete().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path, auth)
        }
    }

    // =========================================================================
    // M09: Expense, Vendor & Accounting Ledger
    // =========================================================================

    fun observeLedger(): Flow<List<LedgerEntry>> = observeCollection("ledger", LedgerEntry::class.java)

    suspend fun saveLedgerEntry(entry: LedgerEntry) {
        val path = "ledger/${entry.id}"
        try {
            db.collection("ledger").document(entry.id).set(entry.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    suspend fun deleteLedgerEntry(entryId: String) {
        val path = "ledger/$entryId"
        try {
            db.collection("ledger").document(entryId).delete().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path, auth)
        }
    }

    // =========================================================================
    // M10: Exams, Question Bank & Mark Sheets
    // =========================================================================

    fun observeExams(): Flow<List<ExamRecord>> = observeCollection("exams", ExamRecord::class.java)
    fun observeExamResults(): Flow<List<ExamResult>> = observeCollection("examResults", ExamResult::class.java)

    suspend fun saveExam(exam: ExamRecord) {
        val path = "exams/${exam.id}"
        try {
            db.collection("exams").document(exam.id).set(exam.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    suspend fun saveExamResult(result: ExamResult) {
        val path = "examResults/${result.id}"
        try {
            db.collection("examResults").document(result.id).set(result.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    /**
     * [A5 + B2] Writes Master Answer Keys to Admin/Server-only `/examAnswerKeys/{examId}`.
     */
    suspend fun saveExamAnswerKey(
        examId: String,
        examTitle: String,
        batchId: String,
        answersByMasterQId: Map<String, Int>,
        ownerUid: String
    ) {
        val path = "examAnswerKeys/$examId"
        try {
            val payload = mapOf(
                "examId" to examId,
                "examTitle" to examTitle,
                "batchId" to batchId,
                "answers" to answersByMasterQId,
                "totalQuestions" to answersByMasterQId.size,
                "ownerUid" to ownerUid,
                "updatedAt" to com.google.firebase.Timestamp.now()
            )
            db.collection("examAnswerKeys").document(examId).set(payload).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    // =========================================================================
    // M11: Study Material, Assignments & Homework
    // =========================================================================

    fun observeMaterials(): Flow<List<StudyMaterial>> = observeCollection("materials", StudyMaterial::class.java)

    suspend fun saveMaterial(material: StudyMaterial) {
        val path = "materials/${material.id}"
        try {
            db.collection("materials").document(material.id).set(material.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    suspend fun deleteMaterial(materialId: String) {
        val path = "materials/$materialId"
        try {
            db.collection("materials").document(materialId).delete().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path, auth)
        }
    }

    // =========================================================================
    // M12: Certificates, Verifiable QR & ID Cards
    // =========================================================================

    fun observeCertificates(): Flow<List<CertificateRecord>> = observeCollection("certificates", CertificateRecord::class.java)

    suspend fun saveCertificate(cert: CertificateRecord) {
        val path = "certificates/${cert.id}"
        try {
            db.collection("certificates").document(cert.id).set(cert.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    // =========================================================================
    // M13 & M14: Staff Directory, HR & Payroll
    // =========================================================================

    fun observeStaff(): Flow<List<StaffMember>> = observeCollection("staff", StaffMember::class.java)
    fun observePayroll(): Flow<List<PayrollEntry>> = observeCollection("payroll", PayrollEntry::class.java)

    suspend fun saveStaff(member: StaffMember) {
        saveStaffWithConfirmation(member).getOrElse { e ->
            if (e is FirestoreOperationException) throw e
            if (!isOfflineOrNetworkError(e) && auth.currentUser != null) {
                handleFirestoreError(e, OperationType.WRITE, "staff/${member.id}", auth)
            }
        }
    }

    suspend fun saveStaffWithConfirmation(
        member: StaffMember,
        timeoutMs: Long = 2_500L
    ): Result<Unit> = runCatching {
        // Update local cache immediately so newly added teachers appear right away
        localStaffCache[member.id] = member
        val path = "staff/${member.id}"
        try {
            val writeTask = db.collection("staff").document(member.id).set(member.toMap())
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs.coerceAtMost(2_500L)) {
                writeTask.await()
            }
        } catch (e: Exception) {
            Log.w("InstituteRepository", "Deferred cloud sync for staff at $path: ${e.message}")
        }
    }

    suspend fun savePayroll(entry: PayrollEntry) {
        val path = "payroll/${entry.id}"
        try {
            db.collection("payroll").document(entry.id).set(entry.toMap()).await()
        } catch (e: Exception) {
            if (!isOfflineOrNetworkError(e)) {
                handleFirestoreError(e, OperationType.WRITE, path, auth)
            }
        }
    }

    // =========================================================================
    // M15: Guardians & Parent Engagement
    // =========================================================================

    fun observeGuardians(): Flow<List<GuardianLink>> = observeCollection("guardians", GuardianLink::class.java)

    suspend fun saveGuardian(guardian: GuardianLink) {
        saveGuardianWithConfirmation(guardian).getOrElse { e ->
            if (e is FirestoreOperationException) throw e
            if (!isOfflineOrNetworkError(e) && auth.currentUser != null) {
                handleFirestoreError(e, OperationType.WRITE, "guardians/${guardian.id}", auth)
            }
        }
    }

    suspend fun saveGuardianWithConfirmation(
        guardian: GuardianLink,
        timeoutMs: Long = 2_500L
    ): Result<Unit> = runCatching {
        // Update local cache immediately
        localGuardiansCache[guardian.id] = guardian
        val path = "guardians/${guardian.id}"
        try {
            val writeTask = db.collection("guardians").document(guardian.id).set(guardian.toMap())
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs.coerceAtMost(2_500L)) {
                writeTask.await()
            }
        } catch (e: Exception) {
            Log.w("InstituteRepository", "Deferred cloud sync for guardian at $path: ${e.message}")
        }
    }

    // =========================================================================
    // M16: Notifications, Broadcasts & Announcements
    // =========================================================================

    fun observeAnnouncements(): Flow<List<AnnouncementItem>> = observeCollection("announcements", AnnouncementItem::class.java)

    suspend fun saveAnnouncement(item: AnnouncementItem) {
        val path = "announcements/${item.id}"
        try {
            db.collection("announcements").document(item.id).set(item.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    // =========================================================================
    // M17: Live Classes, Lab Bookings & Polls
    // =========================================================================

    fun observeLiveClasses(): Flow<List<LiveClassSession>> = observeCollection("liveClasses", LiveClassSession::class.java)

    suspend fun saveLiveClass(session: LiveClassSession) {
        val path = "liveClasses/${session.id}"
        try {
            db.collection("liveClasses").document(session.id).set(session.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    // =========================================================================
    // M18: AI Tutor & Admin AI Assistant Logs
    // =========================================================================

    fun observeAiLogs(): Flow<List<AiInteractionLog>> = observeCollection("aiLogs", AiInteractionLog::class.java)

    suspend fun saveAiLog(log: AiInteractionLog) {
        val path = "aiLogs/${log.id}"
        try {
            db.collection("aiLogs").document(log.id).set(log.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    // =========================================================================
    // M20: Inventory, Assets, Lab PCs & Library
    // =========================================================================

    fun observeAssets(): Flow<List<InventoryAsset>> = observeCollection("assets", InventoryAsset::class.java)

    suspend fun saveAsset(asset: InventoryAsset) {
        val path = "assets/${asset.id}"
        try {
            db.collection("assets").document(asset.id).set(asset.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    // =========================================================================
    // M21: Helpdesk, Support Tickets & NPS Feedback
    // =========================================================================

    fun observeTickets(): Flow<List<SupportTicket>> = observeCollection("tickets", SupportTicket::class.java)

    suspend fun saveTicket(ticket: SupportTicket) {
        val path = "tickets/${ticket.id}"
        try {
            db.collection("tickets").document(ticket.id).set(ticket.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    // =========================================================================
    // M22: Security Audit Log
    // =========================================================================

    fun observeAuditLogs(): Flow<List<SecurityAuditLog>> = observeCollection("auditLogs", SecurityAuditLog::class.java)

    suspend fun logAuditEvent(log: SecurityAuditLog) {
        val path = "auditLogs/${log.id}"
        try {
            db.collection("auditLogs").document(log.id).set(log.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path, auth)
        }
    }

    fun observeBackupsAndConfigs(): Flow<List<InstituteBackupOrConfig>> =
        observeCollection("backups", InstituteBackupOrConfig::class.java)

    suspend fun saveBackupOrConfig(record: InstituteBackupOrConfig) {
        val path = "backups/${record.id}"
        try {
            db.collection("backups").document(record.id).set(record.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    // =========================================================================
    // Enterprise Extensions: feesLedger, teacherAttendance, staffPayroll, immutableLedger
    // =========================================================================

    fun observeFeesLedger(): Flow<List<FeesLedgerRecord>> =
        observeCollection("feesLedger", FeesLedgerRecord::class.java)

    suspend fun saveFeesLedgerRecord(record: FeesLedgerRecord) {
        val path = "feesLedger/${record.id}"
        try {
            db.collection("feesLedger").document(record.id).set(record.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    fun observeTeacherAttendance(): Flow<List<TeacherAttendanceRecord>> =
        observeCollection("teacherAttendance", TeacherAttendanceRecord::class.java)

    suspend fun recordTeacherFaceBiometricAttendance(record: TeacherAttendanceRecord) {
        val path = "teacherAttendance/${record.id}"
        try {
            db.collection("teacherAttendance").document(record.id).set(record.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    fun observeStaffPayroll(): Flow<List<StaffPayrollRecord>> =
        observeCollection("staffPayroll", StaffPayrollRecord::class.java)

    suspend fun saveStaffPayroll(record: StaffPayrollRecord) {
        val path = "staffPayroll/${record.id}"
        try {
            db.collection("staffPayroll").document(record.id).set(record.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    fun observeImmutableLedger(): Flow<List<ImmutableLedgerRecord>> =
        observeCollection("immutableLedger", ImmutableLedgerRecord::class.java)

    suspend fun appendImmutableLedgerRecord(record: ImmutableLedgerRecord) {
        val path = "immutableLedger/${record.id}"
        try {
            db.collection("immutableLedger").document(record.id).set(record.toMap()).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path, auth)
        }
    }

    private fun <T> observeCollection(
        collectionName: String,
        clazz: Class<T>,
        limit: Long = 500L
    ): Flow<List<T>> = callbackFlow {
        val reg = db.collection(collectionName)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(limit)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    close(err)
                    return@addSnapshotListener
                }
                val items = snap?.documents?.mapNotNull { it.toObject(clazz) } ?: emptyList()
                trySend(items)
            }
        awaitClose { reg.remove() }
    }
}
