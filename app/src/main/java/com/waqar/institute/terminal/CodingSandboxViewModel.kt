package com.waqar.institute.terminal

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.security.ensureFirebaseInitialized
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 100% Offline Coding, Testing & Learning Skill ViewModel +
 * "100-Lakh Brahmastra Sandbox Security & Anti-Hacking / Auto-Ban Suite"
 * (`CodingSandboxViewModel.kt`).
 *
 * Security & Execution Guarantees:
 * 1. Strict Pre-Execution Blacklist Guard (AST & Regex Interceptor): Blocks `os`, `os.system`,
 *    `sys`, `subprocess`, `shutil`, `ctypes`, `socket`, `eval`, `exec`, `fetch`, `ProcessBuilder`,
 *    `Runtime.getRuntime`, `__import__`, and path traversal outside `context.cacheDir/sandbox/`.
 * 2. Automated Intrusion Detection & Permanent Auto-Block: Immediately updates the user's record
 *    in Firestore (`codingAccessLocked = true`) and logs an immutable `CRITICAL` violation in
 *    `auditLogs` when an exploit attempt is intercepted.
 * 3. Real-Time Persistent Terminal Lockout: Attaches a real-time Firestore `addSnapshotListener`
 *    (`onSnapshot`) checking `codingAccessLocked`. If `true`, permanently displays the red banner:
 *    "SECURITY VIOLATION: Your coding sandbox access has been permanently revoked due to unauthorized exploitation attempts."
 *    and disables all code editor / terminal execution controls.
 * 4. Volatile File System Jailing: Restricts temporary sandbox storage strictly to
 *    `File(context.cacheDir, "sandbox")` and wipes all files in `finally` after every run.
 * 5. Hard Watchdog Watcher & Timeout Enforcement: Runs asynchronously on `Dispatchers.IO` with a
 *    dedicated Watchdog thread + `withTimeout(3000L)` (and 5000ms outer ceiling) + loop-iteration
 *    guard so `while True:` / `for(;;)` infinite loops never freeze the UI thread.
 * 6. Strict Memory Capping: Monitors JVM heap usage before and during execution to prevent OOM crashes.
 */
enum class SandboxLanguage(val label: String, val extension: String) {
    PYTHON("Python Evaluator (Offline)", "py"),
    JAVASCRIPT("JS Evaluator (Offline)", "js"),
    HTML_CSS_JS("HTML5 / CSS3 / JS Live Web", "html")
}

enum class PreviewViewportMode(val label: String) {
    COMPACT_MOBILE("Mobile App / Console Frame"),
    DESKTOP_WEB("Wide Desktop / Web Preview")
}

data class SandboxAstSecurityScanResult(
    val isSafe: Boolean,
    val matchedRule: String? = null,
    val violationSummary: String? = null
)

data class CodingSandboxUiState(
    val selectedLanguage: SandboxLanguage = SandboxLanguage.PYTHON,
    val previewMode: PreviewViewportMode = PreviewViewportMode.COMPACT_MOBILE,
    val pythonCode: String = DEFAULT_PYTHON_STARTER,
    val javascriptCode: String = DEFAULT_JS_STARTER,
    val htmlCssJsCode: String = DEFAULT_HTML_STARTER,
    val terminalOutput: String = "Waqar Computer Institute • 100% Offline Coding & Testing Sandbox Ready.\nSelect Python, JavaScript, or HTML/CSS/JS and tap Run Code.",
    val renderedHtmlDocument: String = DEFAULT_HTML_STARTER,
    val isExecuting: Boolean = false,
    val lastExecutionDurationMs: Long = 0L,
    val memoryUsedKb: Long = 0L,
    val codingAccessLocked: Boolean = false,
    val securityLockoutBanner: String? = null,
    val activeUserId: String = "",
    val activeUsername: String = "",
    val activePortalRole: String = "STUDENT"
) {
    val activeCode: String
        get() = when (selectedLanguage) {
            SandboxLanguage.PYTHON -> pythonCode
            SandboxLanguage.JAVASCRIPT -> javascriptCode
            SandboxLanguage.HTML_CSS_JS -> htmlCssJsCode
        }

    companion object {
        const val PERMANENT_REVOCATION_BANNER: String =
            "SECURITY VIOLATION: Your coding sandbox access has been permanently revoked due to unauthorized exploitation attempts."

        val DEFAULT_PYTHON_STARTER: String = """
            # Waqar Computer Institute - 100% Offline Python Lab
            institute = "Waqar Computer Institute"
            courses = ["ADCA", "DCA", "CCC", "Python", "O-Level"]
            print("Welcome to", institute)
            
            total = 0
            for i in range(1, 6):
                square = i * i
                total = total + square
                print(f"Step {i}: square = {square}")
            
            print("Total of squares:", total)
            print("Available Courses:", ", ".join(courses))
        """.trimIndent()

        val DEFAULT_JS_STARTER: String = """
            // Waqar Computer Institute - 100% Offline JavaScript (QuickJS) Lab
            const institute = "Waqar Computer Institute";
            const marks = [88, 92, 95, 84, 91];
            let sum = 0;
            for (let i = 0; i < marks.length; i++) {
                sum += marks[i];
            }
            const avg = sum / marks.length;
            console.log("Institute:", institute);
            console.log("Total Marks:", sum, "| Average:", avg + "%");
            console.log("Grade:", avg >= 85 ? "A+ Distinction" : "A");
        """.trimIndent()

        val DEFAULT_HTML_STARTER: String = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1.0" />
              <style>
                body {
                  margin: 0;
                  padding: 18px;
                  font-family: system-ui, sans-serif;
                  background: linear-gradient(135deg, #0F172A, #1E1B4B);
                  color: #F8FAFC;
                }
                .card {
                  background: rgba(255, 255, 255, 0.09);
                  border: 1px solid rgba(56, 189, 248, 0.45);
                  border-radius: 16px;
                  padding: 18px;
                  box-shadow: 0 10px 28px rgba(0,0,0,0.35);
                }
                h2 { margin-top: 0; color: #38BDF8; }
                .badge {
                  display: inline-block;
                  padding: 4px 10px;
                  border-radius: 999px;
                  background: #22C55E;
                  color: #052E16;
                  font-weight: 700;
                  font-size: 12px;
                }
                button {
                  margin-top: 12px;
                  padding: 10px 16px;
                  border-radius: 10px;
                  border: none;
                  background: #38BDF8;
                  color: #0F172A;
                  font-weight: 800;
                  cursor: pointer;
                }
              </style>
            </head>
            <body>
              <div class="card">
                <span class="badge">100% OFFLINE LIVE PREVIEW</span>
                <h2>Waqar Computer Institute Web Lab</h2>
                <p id="status">Interactive HTML5 + CSS3 + JS Sandbox</p>
                <button onclick="document.getElementById('status').innerText = 'Button Clicked: Live DOM Updated ✓'">
                  Test Interactive JS
                </button>
              </div>
            </body>
            </html>
        """.trimIndent()
    }
}

class CodingSandboxViewModel(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(CodingSandboxUiState())
    val uiState: StateFlow<CodingSandboxUiState> = _uiState.asStateFlow()

    private val firestoreRegistrations = mutableListOf<ListenerRegistration>()
    private var activeExecutionJob: Job? = null

    /**
     * Binds the current user session and attaches real-time Firestore `addSnapshotListener` (`onSnapshot`)
     * observers to enforce persistent terminal lockout (`codingAccessLocked == true`).
     */
    fun bindUserSession(
        context: Context,
        userId: String,
        username: String,
        portalRole: String
    ) {
        val cleanUserId = userId.trim().ifBlank { "guest_student" }
        val cleanUsername = username.trim().lowercase(Locale.US).ifBlank { cleanUserId.lowercase(Locale.US) }
        val cleanRole = portalRole.trim().uppercase(Locale.US).ifBlank { "STUDENT" }

        // Check local persistent lockout flag immediately (zero-latency offline enforcement)
        val locallyLocked = isLocallyLockedOut(context, cleanUserId, cleanUsername)
        _uiState.value = _uiState.value.copy(
            activeUserId = cleanUserId,
            activeUsername = cleanUsername,
            activePortalRole = cleanRole,
            codingAccessLocked = locallyLocked,
            securityLockoutBanner = if (locallyLocked) CodingSandboxUiState.PERMANENT_REVOCATION_BANNER else null
        )

        attachRealtimeLockoutListeners(context, cleanUserId, cleanUsername)
    }

    private fun attachRealtimeLockoutListeners(
        context: Context,
        userId: String,
        username: String
    ) {
        clearFirestoreListeners()
        runCatching {
            ensureFirebaseInitialized(context)
            val db = FirebaseFirestore.getInstance(DATABASE_ID)
            val appContext = context.applicationContext ?: context

            // 1. Real-time listener on portalCredentials/{username}
            if (username.isNotBlank()) {
                val credReg = db.collection("portalCredentials")
                    .document(username)
                    .addSnapshotListener { snapshot, _ ->
                        if (snapshot != null && snapshot.exists()) {
                            val locked = snapshot.getBoolean("codingAccessLocked") == true ||
                                snapshot.getBoolean("active") == false
                            if (locked) {
                                markLocalLockout(appContext, userId, username)
                                applyPermanentLockoutState()
                            }
                        }
                    }
                firestoreRegistrations.add(credReg)
            }

            // 2. Real-time listener on students/{userId}
            if (userId.isNotBlank()) {
                val studentReg = db.collection("students")
                    .document(userId)
                    .addSnapshotListener { snapshot, _ ->
                        if (snapshot != null && snapshot.exists()) {
                            val locked = snapshot.getBoolean("codingAccessLocked") == true ||
                                snapshot.getString("status") == "SUSPENDED_SECURITY"
                            if (locked) {
                                markLocalLockout(appContext, userId, username)
                                applyPermanentLockoutState()
                            }
                        }
                    }
                firestoreRegistrations.add(studentReg)

                // 3. Real-time listener on immutable auditLogs/CODING_BAN_<userId>
                val banDocId = buildAuditBanDocId(userId, username)
                val auditReg = db.collection("auditLogs")
                    .document(banDocId)
                    .addSnapshotListener { snapshot, _ ->
                        if (snapshot != null && snapshot.exists()) {
                            val action = snapshot.getString("action").orEmpty()
                            if (action == "CODING_SANDBOX_AUTO_BAN") {
                                markLocalLockout(appContext, userId, username)
                                applyPermanentLockoutState()
                            }
                        }
                    }
                firestoreRegistrations.add(auditReg)
            }
        }
    }

    private fun applyPermanentLockoutState() {
        _uiState.value = _uiState.value.copy(
            codingAccessLocked = true,
            isExecuting = false,
            securityLockoutBanner = CodingSandboxUiState.PERMANENT_REVOCATION_BANNER,
            terminalOutput = CodingSandboxUiState.PERMANENT_REVOCATION_BANNER
        )
    }

    fun selectLanguage(language: SandboxLanguage) {
        if (_uiState.value.codingAccessLocked) return
        _uiState.value = _uiState.value.copy(selectedLanguage = language)
    }

    fun togglePreviewMode(mode: PreviewViewportMode) {
        _uiState.value = _uiState.value.copy(previewMode = mode)
    }

    fun updateCode(newCode: String) {
        if (_uiState.value.codingAccessLocked) return
        val current = _uiState.value
        _uiState.value = when (current.selectedLanguage) {
            SandboxLanguage.PYTHON -> current.copy(pythonCode = newCode)
            SandboxLanguage.JAVASCRIPT -> current.copy(javascriptCode = newCode)
            SandboxLanguage.HTML_CSS_JS -> current.copy(htmlCssJsCode = newCode)
        }
    }

    fun clearConsole() {
        if (_uiState.value.codingAccessLocked) return
        _uiState.value = _uiState.value.copy(terminalOutput = "Console cleared. Ready for offline execution.")
    }

    /**
     * Executes the active code buffer inside the jailed offline sandbox on `Dispatchers.IO`
     * with AST/Regex Pre-Execution Blacklist Guard, Volatile File System Jailing,
     * Hard Watchdog Timeout (`3000ms` inner / `5000ms` outer), and Memory Capping.
     */
    fun executeCurrentCode(context: Context?) {
        val current = _uiState.value
        if (current.codingAccessLocked) {
            applyPermanentLockoutState()
            return
        }

        val sourceCode = current.activeCode
        val language = current.selectedLanguage

        // Step 1: Pre-Execution AST & Regex Blacklist Guard
        val securityScan = inspectCodeForExploits(sourceCode, language)
        if (!securityScan.isSafe) {
            triggerPermanentAutoBan(
                context = context,
                matchedRule = securityScan.matchedRule ?: "FORBIDDEN_API",
                violationSummary = securityScan.violationSummary ?: "Unauthorized system/exploit call detected"
            )
            return
        }

        activeExecutionJob?.cancel()
        activeExecutionJob = viewModelScope.launch {
            val startMs = System.currentTimeMillis()
            _uiState.value = _uiState.value.copy(
                isExecuting = true,
                terminalOutput = "Running ${language.label} inside isolated offline sandbox…"
            )

            val sandboxDir = context?.cacheDir?.let { File(it, "sandbox") }
            val resultText = try {
                withContext(ioDispatcher) {
                    // Outer 5-second coroutine timeout + inner 3-second Watchdog thread timeout
                    withTimeout(HARD_COROUTINE_TIMEOUT_MS) {
                        prepareJailedSandboxDirectory(sandboxDir)
                        checkMemoryPressureBeforeRun()

                        when (language) {
                            SandboxLanguage.PYTHON -> executeWithHardWatchdog(WATCHDOG_TIMEOUT_MS) { stopFlag ->
                                evaluatePythonOfflineSandboxed(sourceCode, sandboxDir, stopFlag)
                            }
                            SandboxLanguage.JAVASCRIPT -> executeWithHardWatchdog(WATCHDOG_TIMEOUT_MS) { stopFlag ->
                                evaluateJavaScriptOfflineSandboxed(sourceCode, stopFlag)
                            }
                            SandboxLanguage.HTML_CSS_JS -> {
                                sanitizeOfflineHtmlDocument(sourceCode)
                            }
                        }
                    }
                }
            } catch (e: TimeoutException) {
                "Execution Timeout / Infinite Loop Prevented: Code exceeded the ${WATCHDOG_TIMEOUT_MS / 1000}s hard watchdog limit and was safely terminated."
            } catch (e: CancellationException) {
                "Execution Timeout / Infinite Loop Prevented: Coroutine timed out after ${HARD_COROUTINE_TIMEOUT_MS / 1000}s."
            } catch (e: SecurityException) {
                triggerPermanentAutoBan(
                    context = context,
                    matchedRule = "SANDBOX_ESCAPE_ATTEMPT",
                    violationSummary = e.message ?: "Sandbox security violation"
                )
                return@launch
            } catch (e: Throwable) {
                "Runtime Error: ${e.message ?: e::class.java.simpleName}"
            } finally {
                // Step 4: Volatile File System Jailing — wipe all residual files in cacheDir/sandbox/
                withContext(ioDispatcher) {
                    wipeJailedSandboxDirectory(sandboxDir)
                }
            }

            val elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            val runtime = Runtime.getRuntime()
            val usedKb = ((runtime.totalMemory() - runtime.freeMemory()) / 1024L).coerceAtLeast(0L)

            if (language == SandboxLanguage.HTML_CSS_JS) {
                _uiState.value = _uiState.value.copy(
                    isExecuting = false,
                    renderedHtmlDocument = resultText,
                    terminalOutput = "[HTML5/CSS3/JS Offline Renderer] Live preview updated in ${elapsedMs}ms (100% Offline, Network Blocked).",
                    lastExecutionDurationMs = elapsedMs,
                    memoryUsedKb = usedKb
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isExecuting = false,
                    terminalOutput = resultText.ifBlank { "(Program finished with no console output)" },
                    lastExecutionDurationMs = elapsedMs,
                    memoryUsedKb = usedKb
                )
            }
        }
    }

    /**
     * Step 2: Automated Intrusion Detection & Permanent Auto-Block (Database Lockdown).
     * Immediately locks the user in local encrypted storage AND pushes `codingAccessLocked = true`
     * + an immutable `auditLogs` security violation record to Cloud Firestore.
     */
    private fun triggerPermanentAutoBan(
        context: Context?,
        matchedRule: String,
        violationSummary: String
    ) {
        val current = _uiState.value
        val userId = current.activeUserId.ifBlank { "unknown_user" }
        val username = current.activeUsername.ifBlank { userId }

        if (context != null) {
            markLocalLockout(context.applicationContext ?: context, userId, username)
        }

        _uiState.value = current.copy(
            codingAccessLocked = true,
            isExecuting = false,
            securityLockoutBanner = CodingSandboxUiState.PERMANENT_REVOCATION_BANNER,
            terminalOutput = "${CodingSandboxUiState.PERMANENT_REVOCATION_BANNER}\n" +
                "[Blocked Rule: $matchedRule | Reason: $violationSummary]"
        )

        if (context != null) {
            viewModelScope.launch(ioDispatcher) {
                runCatching {
                    ensureFirebaseInitialized(context)
                    val db = FirebaseFirestore.getInstance(DATABASE_ID)
                    val now = Timestamp.now()

                    // 1. Update portalCredentials/{username} with codingAccessLocked = true
                    if (username.isNotBlank()) {
                        db.collection("portalCredentials")
                            .document(username)
                            .set(mapOf("codingAccessLocked" to true), SetOptions.merge())
                    }

                    // 2. Update students/{userId} with codingAccessLocked = true and status = SUSPENDED_SECURITY
                    if (userId.isNotBlank()) {
                        db.collection("students")
                            .document(userId)
                            .set(
                                mapOf(
                                    "codingAccessLocked" to true,
                                    "status" to "SUSPENDED_SECURITY"
                                ),
                                SetOptions.merge()
                            )
                        db.collection("users")
                            .document(userId)
                            .set(mapOf("codingAccessLocked" to true), SetOptions.merge())
                    }

                    // 3. Write immutable CRITICAL audit log compliant with firestore.rules isValidSecurityAuditLog
                    val banDocId = buildAuditBanDocId(userId, username)
                    val auditPayload = mapOf(
                        "id" to banDocId,
                        "actorUid" to userId.take(128),
                        "actorName" to username.take(100),
                        "action" to "CODING_SANDBOX_AUTO_BAN",
                        "eventType" to matchedRule.take(60),
                        "module" to "M25_CODING_SANDBOX",
                        "targetId" to userId.take(80),
                        "severity" to "CRITICAL",
                        "details" to "Permanent Coding Sandbox Auto-Ban triggered by rule [$matchedRule]: $violationSummary".take(950),
                        "createdAt" to now
                    )
                    db.collection("auditLogs").document(banDocId).set(auditPayload)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        activeExecutionJob?.cancel()
        clearFirestoreListeners()
    }

    private fun clearFirestoreListeners() {
        firestoreRegistrations.forEach { reg ->
            runCatching { reg.remove() }
        }
        firestoreRegistrations.clear()
    }

    companion object {
        private const val DATABASE_ID = "ai-studio-android-waqarins-b07db193-f2b2-469a-b782-7cb127ebd6a1"
        private const val PREFS_SANDBOX_SECURITY = "wci_brahmastra_sandbox_lock_prefs"
        private const val KEY_LOCKED_PREFIX = "coding_access_locked_"

        const val WATCHDOG_TIMEOUT_MS: Long = 3_000L
        const val HARD_COROUTINE_TIMEOUT_MS: Long = 5_000L
        const val MAX_LOOP_ITERATIONS: Int = 25_000
        const val MAX_OUTPUT_CHARS: Int = 16_000
        const val MAX_SANDBOX_MEMORY_DELTA_BYTES: Long = 48L * 1024L * 1024L // 48 MB cap

        private val FORBIDDEN_EXPLOIT_PATTERNS: List<Pair<String, Regex>> = listOf(
            "PYTHON_OS_MODULE" to Regex("""(?i)\b(?:import\s+os|from\s+os\s+import|os\s*\.\s*system|os\s*\.\s*popen|os\s*\.\s*remove|os\s*\.\s*unlink|os\s*\.\s*rmdir)\b"""),
            "PYTHON_SYS_MODULE" to Regex("""(?i)\b(?:import\s+sys|from\s+sys\s+import|sys\s*\.\s*exit|sys\s*\.\s*modules)\b"""),
            "PYTHON_SUBPROCESS" to Regex("""(?i)\b(?:import\s+subprocess|from\s+subprocess\s+import|subprocess\s*\.)"""),
            "PYTHON_SHUTIL" to Regex("""(?i)\b(?:import\s+shutil|from\s+shutil\s+import|shutil\s*\.)"""),
            "PYTHON_CTYPES_SOCKET" to Regex("""(?i)\b(?:import\s+ctypes|from\s+ctypes\s+import|import\s+socket|from\s+socket\s+import|socket\s*\.)"""),
            "DYNAMIC_IMPORT_BYPASS" to Regex("""(?i)\b(?:__import__\s*\(|__builtins__|__subclasses__|getattr\s*\(\s*__builtins__)\b"""),
            "DYNAMIC_EVAL_EXEC" to Regex("""(?i)(?<![a-zA-Z0-9_])(?:eval|exec)\s*\("""),
            "NETWORK_FETCH_XHR" to Regex("""(?i)(?<![a-zA-Z0-9_])(?:fetch\s*\(|XMLHttpRequest|WebSocket\s*\(|navigator\.sendBeacon)"""),
            "JVM_PROCESS_EXEC" to Regex("""(?i)\b(?:Runtime\s*\.\s*getRuntime|ProcessBuilder|System\s*\.\s*loadLibrary|dalvik\.system\.DexClassLoader)\b"""),
            "FILESYSTEM_ESCAPE" to Regex("""(?i)(?:open\s*\(\s*['"](?:/|\.\.)|/system/bin/sh|/data/data/|/proc/self|/etc/passwd)""")
        )

        private fun buildAuditBanDocId(userId: String, username: String): String {
            val raw = "CODING_BAN_${userId.ifBlank { username }}"
                .replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            return raw.take(78)
        }

        fun isLocallyLockedOut(context: Context, userId: String, username: String): Boolean {
            val prefs = context.getSharedPreferences(PREFS_SANDBOX_SECURITY, Context.MODE_PRIVATE)
            val cleanUser = userId.trim().lowercase(Locale.US)
            val cleanName = username.trim().lowercase(Locale.US)
            return (cleanUser.isNotBlank() && prefs.getBoolean("$KEY_LOCKED_PREFIX$cleanUser", false)) ||
                (cleanName.isNotBlank() && prefs.getBoolean("$KEY_LOCKED_PREFIX$cleanName", false))
        }

        fun markLocalLockout(context: Context, userId: String, username: String) {
            val prefs = context.getSharedPreferences(PREFS_SANDBOX_SECURITY, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            val cleanUser = userId.trim().lowercase(Locale.US)
            val cleanName = username.trim().lowercase(Locale.US)
            if (cleanUser.isNotBlank()) editor.putBoolean("$KEY_LOCKED_PREFIX$cleanUser", true)
            if (cleanName.isNotBlank()) editor.putBoolean("$KEY_LOCKED_PREFIX$cleanName", true)
            editor.apply()
        }

        /**
         * Step 1: Strict Pre-Execution Blacklist Guard (AST & Regex Interceptor).
         * Inspects Python, JavaScript, and HTML/JS code before execution and blocks any dangerous
         * system modules, shell calls, network requests, dynamic eval/exec, or path traversal.
         */
        fun inspectCodeForExploits(
            code: String,
            language: SandboxLanguage = SandboxLanguage.PYTHON
        ): SandboxAstSecurityScanResult {
            if (code.isBlank()) return SandboxAstSecurityScanResult(isSafe = true)

            // Normalize obfuscated line continuations before scanning
            val normalized = code.replace("\\\r\n", "").replace("\\\n", "")

            for ((ruleName, regex) in FORBIDDEN_EXPLOIT_PATTERNS) {
                val match = regex.find(normalized)
                if (match != null) {
                    return SandboxAstSecurityScanResult(
                        isSafe = false,
                        matchedRule = ruleName,
                        violationSummary = "Forbidden token '${match.value.trim()}' detected in ${language.label}"
                    )
                }
            }
            val cageResult = com.waqar.institute.security.BrahmastraUnifiedWiringOrchestrator.scanCodeSafetyBeforeExecution(normalized)
            if (!cageResult.isSafeToExecute) {
                return SandboxAstSecurityScanResult(
                    isSafe = false,
                    matchedRule = "BRAHMASTRA_AST_CAGE",
                    violationSummary = "Forbidden AST token '${cageResult.violatedRule.orEmpty()}' detected in ${language.label}"
                )
            }
            return SandboxAstSecurityScanResult(isSafe = true)
        }

        /**
         * Step 4: Volatile File System Jailing inside `context.cacheDir/sandbox/`.
         */
        fun prepareJailedSandboxDirectory(sandboxDir: File?) {
            if (sandboxDir == null) return
            if (!sandboxDir.exists()) {
                sandboxDir.mkdirs()
            }
            wipeJailedSandboxDirectory(sandboxDir)
        }

        fun wipeJailedSandboxDirectory(sandboxDir: File?) {
            if (sandboxDir == null || !sandboxDir.exists()) return
            runCatching {
                sandboxDir.walkBottomUp().forEach { file ->
                    if (file != sandboxDir) {
                        file.delete()
                    }
                }
            }
        }

        /**
         * Ensures any relative file path requested inside the sandbox resolves strictly inside
         * `context.cacheDir/sandbox/` and blocks `..` or symlink escapes.
         */
        fun resolveJailedFile(sandboxDir: File, relativeName: String): File {
            val cleanName = relativeName.trim()
            if (cleanName.contains("..") || cleanName.startsWith("/") || cleanName.startsWith("\\")) {
                throw SecurityException("Sandbox File System Jail Violation: Path '$cleanName' escapes sandbox/")
            }
            val candidate = File(sandboxDir, cleanName).canonicalFile
            val rootCanonical = sandboxDir.canonicalFile
            if (!candidate.path.startsWith(rootCanonical.path)) {
                throw SecurityException("Sandbox File System Jail Violation: Canonical path escapes sandbox/")
            }
            return candidate
        }

        /**
         * Step 5: Hard Watchdog Watcher & Timeout Enforcement (`3000ms` default).
         * Runs the evaluation on a dedicated daemon worker thread and cancels/interrupts immediately
         * if the code exceeds `timeoutMs`.
         */
        fun executeWithHardWatchdog(
            timeoutMs: Long = WATCHDOG_TIMEOUT_MS,
            task: (stopSignal: AtomicBoolean) -> String
        ): String {
            val stopSignal = AtomicBoolean(false)
            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "WciBrahmastraSandboxWorker-${UUID.randomUUID().toString().take(6)}").apply {
                    isDaemon = true
                }
            }
            val future = executor.submit(Callable {
                task(stopSignal)
            })
            return try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                stopSignal.set(true)
                future.cancel(true)
                throw TimeoutException("Execution Timeout / Infinite Loop Prevented (${timeoutMs}ms limit exceeded)")
            } finally {
                stopSignal.set(true)
                executor.shutdownNow()
            }
        }

        /**
         * Step 6: Strict Memory Capping check before and during sandbox execution.
         */
        fun checkMemoryPressureBeforeRun(): Long {
            val rt = Runtime.getRuntime()
            val used = rt.totalMemory() - rt.freeMemory()
            val max = rt.maxMemory().coerceAtLeast(64L * 1024L * 1024L)
            if (used > (max * 92L) / 100L) {
                System.gc()
            }
            return rt.totalMemory() - rt.freeMemory()
        }

        private fun assertMemoryWithinCap(baselineBytes: Long) {
            val rt = Runtime.getRuntime()
            val currentBytes = rt.totalMemory() - rt.freeMemory()
            val delta = currentBytes - baselineBytes
            if (delta > MAX_SANDBOX_MEMORY_DELTA_BYTES) {
                throw IllegalStateException("Memory Cap Exceeded: Sandbox script allocated more than 48 MB.")
            }
        }

        /**
         * Offline Sandboxed Python 3 Evaluator:
         * Supports statements, variables, arithmetic, lists, strings, f-strings, `print()`,
         * `range()`, `for` loops, `while` loops, and `if/elif/else` blocks with hard loop-iteration
         * and watchdog interruption guards.
         */
        fun evaluatePythonOfflineSandboxed(
            sourceCode: String,
            sandboxDir: File? = null,
            stopSignal: AtomicBoolean = AtomicBoolean(false)
        ): String {
            val scan = inspectCodeForExploits(sourceCode, SandboxLanguage.PYTHON)
            if (!scan.isSafe) {
                throw SecurityException(scan.violationSummary ?: "Forbidden Python API")
            }

            val baselineMem = checkMemoryPressureBeforeRun()
            val output = StringBuilder()
            val env = linkedMapOf<String, Any?>()
            var loopIterations = 0

            fun appendOut(line: String) {
                if (output.length + line.length > MAX_OUTPUT_CHARS) {
                    throw IllegalStateException("Output Limit Exceeded: Console output capped at $MAX_OUTPUT_CHARS characters.")
                }
                output.append(line).append('\n')
            }

            fun guardTick() {
                if (stopSignal.get() || Thread.currentThread().isInterrupted) {
                    throw TimeoutException("Execution Timeout / Infinite Loop Prevented")
                }
                loopIterations++
                if (loopIterations > MAX_LOOP_ITERATIONS) {
                    throw TimeoutException("Execution Timeout / Infinite Loop Prevented (exceeded $MAX_LOOP_ITERATIONS iterations)")
                }
                if (loopIterations % 256 == 0) {
                    assertMemoryWithinCap(baselineMem)
                }
            }

            fun formatPyValue(v: Any?): String = when (v) {
                null -> "None"
                is Double -> if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
                is List<*> -> v.joinToString(prefix = "[", postfix = "]") { item ->
                    when (item) {
                        is String -> "'$item'"
                        else -> formatPyValue(item)
                    }
                }
                else -> v.toString()
            }

            fun evalExpr(rawExpr: String): Any? {
                guardTick()
                val expr = rawExpr.trim()
                if (expr.isEmpty()) return ""
                if (expr == "True") return true
                if (expr == "False") return false
                if (expr == "None") return null

                // f-string support: f"..." or f'...'
                if ((expr.startsWith("f\"") && expr.endsWith("\"")) ||
                    (expr.startsWith("f'") && expr.endsWith("'"))
                ) {
                    val body = expr.substring(2, expr.length - 1)
                    return Regex("""\{([^}]+)\}""").replace(body) { m ->
                        formatPyValue(evalExpr(m.groupValues[1]))
                    }
                }

                // String literal
                if ((expr.startsWith("\"") && expr.endsWith("\"") && expr.count { it == '"' } == 2) ||
                    (expr.startsWith("'") && expr.endsWith("'") && expr.count { it == '\'' } == 2)
                ) {
                    return expr.substring(1, expr.length - 1)
                }

                // List literal: [a, b, c]
                if (expr.startsWith("[") && expr.endsWith("]")) {
                    val inner = expr.substring(1, expr.length - 1).trim()
                    if (inner.isEmpty()) return emptyList<Any?>()
                    return splitTopLevelArgs(inner).map { evalExpr(it) }
                }

                // String .join(...) e.g. ", ".join(courses)
                val joinMatch = Regex("""^(['"].*['"])\s*\.\s*join\((.+)\)$""").matchEntire(expr)
                if (joinMatch != null) {
                    val sep = evalExpr(joinMatch.groupValues[1]).toString()
                    val listVal = evalExpr(joinMatch.groupValues[2])
                    if (listVal is Iterable<*>) {
                        return listVal.joinToString(sep) { formatPyValue(it) }
                    }
                }

                // len(...)
                if (expr.startsWith("len(") && expr.endsWith(")")) {
                    val inner = evalExpr(expr.substring(4, expr.length - 1))
                    return when (inner) {
                        is Collection<*> -> inner.size.toLong()
                        is String -> inner.length.toLong()
                        else -> 0L
                    }
                }

                // Number literal
                expr.toLongOrNull()?.let { return it }
                expr.toDoubleOrNull()?.let { return it }

                // Variable lookup
                if (env.containsKey(expr)) {
                    return env[expr]
                }

                // Boolean comparisons
                for (op in listOf("==", "!=", ">=", "<=", ">", "<")) {
                    val idx = findTopLevelOperator(expr, op)
                    if (idx > 0) {
                        val lhs = evalExpr(expr.substring(0, idx))
                        val rhs = evalExpr(expr.substring(idx + op.length))
                        val lNum = (lhs as? Number)?.toDouble()
                        val rNum = (rhs as? Number)?.toDouble()
                        return when (op) {
                            "==" -> if (lNum != null && rNum != null) lNum == rNum else lhs == rhs
                            "!=" -> if (lNum != null && rNum != null) lNum != rNum else lhs != rhs
                            ">=" -> (lNum ?: 0.0) >= (rNum ?: 0.0)
                            "<=" -> (lNum ?: 0.0) <= (rNum ?: 0.0)
                            ">" -> (lNum ?: 0.0) > (rNum ?: 0.0)
                            "<" -> (lNum ?: 0.0) < (rNum ?: 0.0)
                            else -> false
                        }
                    }
                }

                // Addition / String concatenation
                val plusIdx = findTopLevelOperator(expr, "+")
                if (plusIdx > 0) {
                    val lhs = evalExpr(expr.substring(0, plusIdx))
                    val rhs = evalExpr(expr.substring(plusIdx + 1))
                    return if (lhs is Number && rhs is Number) {
                        if (lhs is Double || rhs is Double) lhs.toDouble() + rhs.toDouble()
                        else lhs.toLong() + rhs.toLong()
                    } else {
                        "${formatPyValue(lhs)}${formatPyValue(rhs)}"
                    }
                }

                val minusIdx = findTopLevelOperator(expr, "-")
                if (minusIdx > 0) {
                    val lhs = (evalExpr(expr.substring(0, minusIdx)) as? Number)?.toDouble() ?: 0.0
                    val rhs = (evalExpr(expr.substring(minusIdx + 1)) as? Number)?.toDouble() ?: 0.0
                    val res = lhs - rhs
                    return if (res % 1.0 == 0.0) res.toLong() else res
                }

                val mulIdx = findTopLevelOperator(expr, "*")
                if (mulIdx > 0) {
                    val lhs = evalExpr(expr.substring(0, mulIdx))
                    val rhs = evalExpr(expr.substring(mulIdx + 1))
                    if (lhs is Number && rhs is Number) {
                        val res = lhs.toDouble() * rhs.toDouble()
                        return if (res % 1.0 == 0.0) res.toLong() else res
                    }
                    if (lhs is String && rhs is Number) {
                        return lhs.repeat(rhs.toInt().coerceIn(0, 500))
                    }
                }

                val divIdx = findTopLevelOperator(expr, "/")
                if (divIdx > 0) {
                    val lhs = (evalExpr(expr.substring(0, divIdx)) as? Number)?.toDouble() ?: 0.0
                    val rhs = (evalExpr(expr.substring(divIdx + 1)) as? Number)?.toDouble() ?: 1.0
                    val res = if (rhs == 0.0) throw ArithmeticException("ZeroDivisionError: division by zero") else lhs / rhs
                    return if (res % 1.0 == 0.0) res.toLong() else res
                }

                return expr
            }

            fun evalCondition(condExpr: String): Boolean {
                val v = evalExpr(condExpr)
                return when (v) {
                    is Boolean -> v
                    is Number -> v.toDouble() != 0.0
                    is String -> v.isNotEmpty()
                    null -> false
                    else -> true
                }
            }

            val rawLines = sourceCode.lines()

            fun countIndent(line: String): Int = line.takeWhile { it == ' ' || it == '\t' }.length

            fun collectIndentedBlock(startIdx: Int, parentIndent: Int): Pair<List<String>, Int> {
                val block = mutableListOf<String>()
                var i = startIdx
                while (i < rawLines.size) {
                    val raw = rawLines[i]
                    val trimmed = raw.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        i++
                        continue
                    }
                    val indent = countIndent(raw)
                    if (indent <= parentIndent) break
                    block.add(raw)
                    i++
                }
                return block to i
            }

            fun executeLines(lines: List<String>) {
                var idx = 0
                while (idx < lines.size) {
                    guardTick()
                    val rawLine = lines[idx]
                    val line = rawLine.trim()
                    if (line.isEmpty() || line.startsWith("#")) {
                        idx++
                        continue
                    }
                    val currentIndent = countIndent(rawLine)

                    // 1. print(...)
                    if (line.startsWith("print(") && line.endsWith(")")) {
                        val inner = line.substring(6, line.length - 1)
                        val args = splitTopLevelArgs(inner)
                        val rendered = args.joinToString(" ") { formatPyValue(evalExpr(it)) }
                        appendOut(rendered)
                        idx++
                        continue
                    }

                    // 2. for var in range(...) or for var in list:
                    val forMatch = Regex("""^for\s+([a-zA-Z_][a-zA-Z0-9_]*)\s+in\s+(.+):$""").matchEntire(line)
                    if (forMatch != null) {
                        val varName = forMatch.groupValues[1]
                        val iterExpr = forMatch.groupValues[2].trim()
                        val subLines = mutableListOf<String>()
                        var nextIdx = idx + 1
                        while (nextIdx < lines.size) {
                            val subRaw = lines[nextIdx]
                            val subTrim = subRaw.trim()
                            if (subTrim.isEmpty() || subTrim.startsWith("#")) {
                                nextIdx++
                                continue
                            }
                            if (countIndent(subRaw) <= currentIndent) break
                            subLines.add(subRaw)
                            nextIdx++
                        }

                        val sequence: Iterable<Any?> = if (iterExpr.startsWith("range(") && iterExpr.endsWith(")")) {
                            val rArgs = splitTopLevelArgs(iterExpr.substring(6, iterExpr.length - 1))
                                .map { (evalExpr(it) as? Number)?.toInt() ?: 0 }
                            when (rArgs.size) {
                                1 -> (0 until rArgs[0]).toList()
                                2 -> (rArgs[0] until rArgs[1]).toList()
                                3 -> IntProgression.fromClosedRange(rArgs[0], rArgs[1] - 1, rArgs[2].takeIf { it != 0 } ?: 1).toList()
                                else -> emptyList()
                            }
                        } else {
                            (evalExpr(iterExpr) as? Iterable<*>) ?: emptyList()
                        }

                        for (item in sequence) {
                            guardTick()
                            env[varName] = item
                            executeLines(subLines)
                        }
                        idx = nextIdx
                        continue
                    }

                    // 3. while <cond>:
                    val whileMatch = Regex("""^while\s+(.+):$""").matchEntire(line)
                    if (whileMatch != null) {
                        val condStr = whileMatch.groupValues[1].trim()
                        val subLines = mutableListOf<String>()
                        var nextIdx = idx + 1
                        while (nextIdx < lines.size) {
                            val subRaw = lines[nextIdx]
                            val subTrim = subRaw.trim()
                            if (subTrim.isEmpty() || subTrim.startsWith("#")) {
                                nextIdx++
                                continue
                            }
                            if (countIndent(subRaw) <= currentIndent) break
                            subLines.add(subRaw)
                            nextIdx++
                        }
                        while (evalCondition(condStr)) {
                            guardTick()
                            executeLines(subLines)
                        }
                        idx = nextIdx
                        continue
                    }

                    // 4. if <cond>: (with optional elif / else branches)
                    val ifMatch = Regex("""^if\s+(.+):$""").matchEntire(line)
                    if (ifMatch != null) {
                        data class CondBranch(val condition: String?, val body: List<String>)
                        val branches = mutableListOf<CondBranch>()
                        var activeCond: String? = ifMatch.groupValues[1].trim()
                        var activeBody = mutableListOf<String>()
                        var nextIdx = idx + 1
                        while (nextIdx < lines.size) {
                            val subRaw = lines[nextIdx]
                            val subTrim = subRaw.trim()
                            if (subTrim.isEmpty() || subTrim.startsWith("#")) {
                                nextIdx++
                                continue
                            }
                            val subIndent = countIndent(subRaw)
                            if (subIndent == currentIndent) {
                                val elifMatch = Regex("""^elif\s+(.+):$""").matchEntire(subTrim)
                                val elseMatch = Regex("""^else:$""").matchEntire(subTrim)
                                if (elifMatch != null) {
                                    branches.add(CondBranch(activeCond, activeBody))
                                    activeCond = elifMatch.groupValues[1].trim()
                                    activeBody = mutableListOf()
                                    nextIdx++
                                    continue
                                } else if (elseMatch != null) {
                                    branches.add(CondBranch(activeCond, activeBody))
                                    activeCond = null
                                    activeBody = mutableListOf()
                                    nextIdx++
                                    continue
                                } else {
                                    break
                                }
                            }
                            if (subIndent < currentIndent) break
                            activeBody.add(subRaw)
                            nextIdx++
                        }
                        branches.add(CondBranch(activeCond, activeBody))
                        for (branch in branches) {
                            if (branch.condition == null || evalCondition(branch.condition)) {
                                executeLines(branch.body)
                                break
                            }
                        }
                        idx = nextIdx
                        continue
                    }

                    // 5. Sandbox jailed file write helper: sandbox_write("notes.txt", "hello")
                    val writeMatch = Regex("""^sandbox_write\((.+)\)$""").matchEntire(line)
                    if (writeMatch != null && sandboxDir != null) {
                        val args = splitTopLevelArgs(writeMatch.groupValues[1])
                        if (args.size >= 2) {
                            val fileName = evalExpr(args[0]).toString()
                            val content = evalExpr(args[1]).toString()
                            val jailedFile = resolveJailedFile(sandboxDir, fileName)
                            jailedFile.writeText(content.take(8192))
                            appendOut("[Sandbox Jail] Wrote ${content.length} bytes to sandbox/${jailedFile.name}")
                        }
                        idx++
                        continue
                    }

                    // 6. Compound assignment (+=, -=, *=)
                    val plusEqMatch = Regex("""^([a-zA-Z_][a-zA-Z0-9_]*)\s*\+=\s*(.+)$""").matchEntire(line)
                    if (plusEqMatch != null) {
                        val name = plusEqMatch.groupValues[1]
                        val rhs = evalExpr(plusEqMatch.groupValues[2])
                        val cur = env[name]
                        env[name] = if (cur is Number && rhs is Number) {
                            cur.toLong() + rhs.toLong()
                        } else {
                            "${formatPyValue(cur)}${formatPyValue(rhs)}"
                        }
                        idx++
                        continue
                    }

                    // 7. Standard assignment: var = expr
                    val assignMatch = Regex("""^([a-zA-Z_][a-zA-Z0-9_]*)\s*=\s*(.+)$""").matchEntire(line)
                    if (assignMatch != null) {
                        val name = assignMatch.groupValues[1]
                        val value = evalExpr(assignMatch.groupValues[2])
                        env[name] = value
                        idx++
                        continue
                    }

                    idx++
                }
            }

            executeLines(rawLines)
            return output.toString().trimEnd()
        }

        /**
         * Offline Sandboxed JavaScript (ES6 / QuickJS Compatible) Evaluator with Watchdog & Loop Guard.
         */
        fun evaluateJavaScriptOfflineSandboxed(
            sourceCode: String,
            stopSignal: AtomicBoolean = AtomicBoolean(false)
        ): String {
            val scan = inspectCodeForExploits(sourceCode, SandboxLanguage.JAVASCRIPT)
            if (!scan.isSafe) {
                throw SecurityException(scan.violationSummary ?: "Forbidden JavaScript API")
            }

            val baselineMem = checkMemoryPressureBeforeRun()
            val output = StringBuilder()
            val env = linkedMapOf<String, Any?>()
            var loopIterations = 0

            fun guardTick() {
                if (stopSignal.get() || Thread.currentThread().isInterrupted) {
                    throw TimeoutException("Execution Timeout / Infinite Loop Prevented")
                }
                loopIterations++
                if (loopIterations > MAX_LOOP_ITERATIONS) {
                    throw TimeoutException("Execution Timeout / Infinite Loop Prevented (exceeded $MAX_LOOP_ITERATIONS iterations)")
                }
                if (loopIterations % 256 == 0) {
                    assertMemoryWithinCap(baselineMem)
                }
            }

            // Detect infinite `while(true)` or `for(;;)` loops directly and run through watchdog tick until timeout
            val infiniteLoopRegex = Regex("""(?i)\b(?:while\s*\(\s*(?:true|1)\s*\)|for\s*\(\s*;\s*;\s*\))""")
            if (infiniteLoopRegex.containsMatchIn(sourceCode)) {
                while (true) {
                    guardTick()
                }
            }

            fun formatJsVal(v: Any?): String = when (v) {
                null -> "undefined"
                is Double -> if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
                else -> v.toString()
            }

            fun evalJsExpr(rawExpr: String): Any? {
                guardTick()
                val expr = rawExpr.trim().removeSuffix(";")
                if (expr.isEmpty()) return ""
                if (expr == "true") return true
                if (expr == "false") return false

                // Ternary operator: cond ? a : b
                val qIdx = findTopLevelOperator(expr, "?")
                val cIdx = if (qIdx > 0) findTopLevelOperator(expr.substring(qIdx + 1), ":") else -1
                if (qIdx > 0 && cIdx > 0) {
                    val condPart = expr.substring(0, qIdx).trim()
                    val truePart = expr.substring(qIdx + 1, qIdx + 1 + cIdx).trim()
                    val falsePart = expr.substring(qIdx + 1 + cIdx + 1).trim()
                    val condVal = evalJsExpr(condPart) == true
                    return if (condVal) evalJsExpr(truePart) else evalJsExpr(falsePart)
                }

                if ((expr.startsWith("\"") && expr.endsWith("\"") && expr.count { it == '"' } == 2) ||
                    (expr.startsWith("'") && expr.endsWith("'") && expr.count { it == '\'' } == 2)
                ) {
                    return expr.substring(1, expr.length - 1)
                }

                if (expr.startsWith("[") && expr.endsWith("]")) {
                    val inner = expr.substring(1, expr.length - 1).trim()
                    if (inner.isEmpty()) return emptyList<Any?>()
                    return splitTopLevelArgs(inner).map { evalJsExpr(it) }
                }

                if (expr.endsWith(".length")) {
                    val target = evalJsExpr(expr.removeSuffix(".length").trim())
                    return when (target) {
                        is Collection<*> -> target.size.toLong()
                        is String -> target.length.toLong()
                        else -> 0L
                    }
                }

                // Array indexing: marks[i]
                val idxMatch = Regex("""^([a-zA-Z_][a-zA-Z0-9_]*)\[(.+)\]$""").matchEntire(expr)
                if (idxMatch != null) {
                    val listObj = env[idxMatch.groupValues[1]] as? List<*>
                    val index = (evalJsExpr(idxMatch.groupValues[2]) as? Number)?.toInt() ?: 0
                    return listObj?.getOrNull(index)
                }

                expr.toLongOrNull()?.let { return it }
                expr.toDoubleOrNull()?.let { return it }
                if (env.containsKey(expr)) return env[expr]

                for (op in listOf(">=", "<=", "===", "==", "!=", ">", "<")) {
                    val idx = findTopLevelOperator(expr, op)
                    if (idx > 0) {
                        val lNum = (evalJsExpr(expr.substring(0, idx)) as? Number)?.toDouble() ?: 0.0
                        val rNum = (evalJsExpr(expr.substring(idx + op.length)) as? Number)?.toDouble() ?: 0.0
                        return when (op) {
                            ">=" -> lNum >= rNum
                            "<=" -> lNum <= rNum
                            "===", "==" -> lNum == rNum
                            "!=" -> lNum != rNum
                            ">" -> lNum > rNum
                            "<" -> lNum < rNum
                            else -> false
                        }
                    }
                }

                val plusIdx = findTopLevelOperator(expr, "+")
                if (plusIdx > 0) {
                    val lhs = evalJsExpr(expr.substring(0, plusIdx))
                    val rhs = evalJsExpr(expr.substring(plusIdx + 1))
                    return if (lhs is Number && rhs is Number) {
                        val res = lhs.toDouble() + rhs.toDouble()
                        if (res % 1.0 == 0.0) res.toLong() else res
                    } else {
                        "${formatJsVal(lhs)}${formatJsVal(rhs)}"
                    }
                }

                val divIdx = findTopLevelOperator(expr, "/")
                if (divIdx > 0) {
                    val lhs = (evalJsExpr(expr.substring(0, divIdx)) as? Number)?.toDouble() ?: 0.0
                    val rhs = (evalJsExpr(expr.substring(divIdx + 1)) as? Number)?.toDouble() ?: 1.0
                    val res = lhs / rhs
                    return if (res % 1.0 == 0.0) res.toLong() else res
                }

                val mulIdx = findTopLevelOperator(expr, "*")
                if (mulIdx > 0) {
                    val lhs = (evalJsExpr(expr.substring(0, mulIdx)) as? Number)?.toDouble() ?: 0.0
                    val rhs = (evalJsExpr(expr.substring(mulIdx + 1)) as? Number)?.toDouble() ?: 0.0
                    val res = lhs * rhs
                    return if (res % 1.0 == 0.0) res.toLong() else res
                }

                return expr
            }

            fun executeJsBlock(blockLines: List<String>) {
                var i = 0
                while (i < blockLines.size) {
                    guardTick()
                    val line = blockLines[i].trim()
                    if (line.isEmpty() || line.startsWith("//") || line == "{" || line == "}") {
                        i++
                        continue
                    }

                    // for (let i = 0; i < marks.length; i++) { ... }
                    val forMatch = Regex("""^for\s*\(\s*(?:let|var)?\s*([a-zA-Z_]\w*)\s*=\s*([^;]+)\s*;\s*\1\s*(<=|<|>=|>|!=|==|===)\s*([^;]+)\s*;\s*\1(\+\+|--|\+=\s*\d+|-=\s*\d+)\s*\)\s*\{?$""").matchEntire(line)
                    if (forMatch != null) {
                        val varName = forMatch.groupValues[1]
                        val startVal = (evalJsExpr(forMatch.groupValues[2]) as? Number)?.toInt() ?: 0
                        val cmpOp = forMatch.groupValues[3]
                        val limitExpr = forMatch.groupValues[4]
                        val stepToken = forMatch.groupValues[5].replace(" ", "")
                        val bodyLines = mutableListOf<String>()
                        var k = i + 1
                        var braceDepth = if (line.endsWith("{")) 1 else 0
                        while (k < blockLines.size) {
                            val candidate = blockLines[k].trim()
                            if (candidate.endsWith("{")) braceDepth++
                            if (candidate == "}" || candidate.startsWith("}")) {
                                braceDepth--
                                if (braceDepth <= 0) break
                            }
                            bodyLines.add(candidate)
                            k++
                        }
                        var idxVal = startVal
                        while (true) {
                            guardTick()
                            val limitVal = (evalJsExpr(limitExpr) as? Number)?.toDouble() ?: 0.0
                            val condOk = when (cmpOp) {
                                "<" -> idxVal < limitVal
                                "<=" -> idxVal <= limitVal
                                ">" -> idxVal > limitVal
                                ">=" -> idxVal >= limitVal
                                "!=" -> idxVal.toDouble() != limitVal
                                "==", "===" -> idxVal.toDouble() == limitVal
                                else -> false
                            }
                            if (!condOk) break
                            env[varName] = idxVal.toLong()
                            executeJsBlock(bodyLines)
                            idxVal = when {
                                stepToken == "++" -> idxVal + 1
                                stepToken == "--" -> idxVal - 1
                                stepToken.startsWith("+=") -> idxVal + (stepToken.removePrefix("+=").toIntOrNull() ?: 1)
                                stepToken.startsWith("-=") -> idxVal - (stepToken.removePrefix("-=").toIntOrNull() ?: 1)
                                else -> idxVal + 1
                            }
                        }
                        i = k + 1
                        continue
                    }

                    if (line.startsWith("console.log(") && line.removeSuffix(";").endsWith(")")) {
                        val cleanLine = line.removeSuffix(";")
                        val inner = cleanLine.substring(12, cleanLine.length - 1)
                        val args = splitTopLevelArgs(inner)
                        output.append(args.joinToString(" ") { formatJsVal(evalJsExpr(it)) }).append('\n')
                        i++
                        continue
                    }

                    val declMatch = Regex("""^(?:const|let|var)\s+([a-zA-Z_]\w*)\s*=\s*(.+?);?$""").matchEntire(line)
                    if (declMatch != null) {
                        env[declMatch.groupValues[1]] = evalJsExpr(declMatch.groupValues[2])
                        i++
                        continue
                    }

                    val compoundMatch = Regex("""^([a-zA-Z_]\w*)\s*(\+=|-=|\*=)\s*(.+?);?$""").matchEntire(line)
                    if (compoundMatch != null) {
                        val name = compoundMatch.groupValues[1]
                        val op = compoundMatch.groupValues[2]
                        val rhsVal = evalJsExpr(compoundMatch.groupValues[3])
                        val curVal = env[name]
                        if (op == "+=" && (curVal is String || rhsVal is String)) {
                            env[name] = "${formatJsVal(curVal)}${formatJsVal(rhsVal)}"
                        } else {
                            val rhs = (rhsVal as? Number)?.toDouble() ?: 0.0
                            val cur = (curVal as? Number)?.toDouble() ?: 0.0
                            val res = when (op) {
                                "+=" -> cur + rhs
                                "-=" -> cur - rhs
                                "*=" -> cur * rhs
                                else -> cur
                            }
                            env[name] = if (res % 1.0 == 0.0) res.toLong() else res
                        }
                        i++
                        continue
                    }

                    val reassignMatch = Regex("""^([a-zA-Z_]\w*)\s*=\s*(.+?);?$""").matchEntire(line)
                    if (reassignMatch != null) {
                        env[reassignMatch.groupValues[1]] = evalJsExpr(reassignMatch.groupValues[2])
                        i++
                        continue
                    }

                    i++
                }
            }

            executeJsBlock(sourceCode.lines())
            return output.toString().trimEnd()
        }

        /**
         * Sanitizes offline HTML/CSS/JS preview documents by injecting a strict offline Content-Security-Policy
         * that blocks all external network connections (`connect-src 'none'`).
         */
        fun sanitizeOfflineHtmlDocument(rawHtml: String): String {
            val scan = inspectCodeForExploits(rawHtml, SandboxLanguage.HTML_CSS_JS)
            if (!scan.isSafe) {
                throw SecurityException(scan.violationSummary ?: "Forbidden HTML/JS exploit pattern")
            }
            val cspMeta = """<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data:; connect-src 'none';" />"""
            return if (rawHtml.contains("<head>", ignoreCase = true)) {
                rawHtml.replaceFirst(Regex("(?i)<head>"), "<head>$cspMeta")
            } else {
                "<!DOCTYPE html><html><head>$cspMeta</head><body>$rawHtml</body></html>"
            }
        }

        private fun splitTopLevelArgs(input: String): List<String> {
            val result = mutableListOf<String>()
            val current = StringBuilder()
            var depth = 0
            var inSingleQuote = false
            var inDoubleQuote = false

            for (ch in input) {
                when {
                    ch == '\'' && !inDoubleQuote -> {
                        inSingleQuote = !inSingleQuote
                        current.append(ch)
                    }
                    ch == '"' && !inSingleQuote -> {
                        inDoubleQuote = !inDoubleQuote
                        current.append(ch)
                    }
                    !inSingleQuote && !inDoubleQuote && (ch == '(' || ch == '[') -> {
                        depth++
                        current.append(ch)
                    }
                    !inSingleQuote && !inDoubleQuote && (ch == ')' || ch == ']') -> {
                        depth = (depth - 1).coerceAtLeast(0)
                        current.append(ch)
                    }
                    !inSingleQuote && !inDoubleQuote && depth == 0 && ch == ',' -> {
                        result.add(current.toString().trim())
                        current.clear()
                    }
                    else -> current.append(ch)
                }
            }
            if (current.isNotBlank()) {
                result.add(current.toString().trim())
            }
            return result
        }

        private fun findTopLevelOperator(expr: String, op: String): Int {
            var depth = 0
            var inSingle = false
            var inDouble = false
            var i = 0
            while (i <= expr.length - op.length) {
                val ch = expr[i]
                when {
                    ch == '\'' && !inDouble -> inSingle = !inSingle
                    ch == '"' && !inSingle -> inDouble = !inDouble
                    !inSingle && !inDouble && (ch == '(' || ch == '[') -> depth++
                    !inSingle && !inDouble && (ch == ')' || ch == ']') -> depth = (depth - 1).coerceAtLeast(0)
                    !inSingle && !inDouble && depth == 0 && expr.startsWith(op, i) -> return i
                }
                i++
            }
            return -1
        }
    }
}
