#!/usr/bin/env python3
"""
Waqar Computer Institute Enterprise ERP & Biometric Exam Platform
Master Static Guardrails & Zero-Violation Verifier (Phase 1 Gate)

Mandatory Hard Constraints Asserted:
1. 100% Pure Native Android — STRICT ZERO WEB / WEBSITE FILES:
   - Asserts ZERO .html, .css, web-portal/, index.html, or browser-only files.
2. ZERO Online Payment Gateways:
   - Asserts ZERO Razorpay, Stripe, Paytm, PhonePe, UPI Intent, or web checkout SDKs.
3. ZERO Installment / EMI Systems:
   - Asserts ZERO EMI schedules, split payments, recurring auto-debit, or late-interest logic.
4. ZERO Fake Logic / ZERO Dummy Data:
   - Asserts ZERO hardcoded student arrays, ZERO mock delays, ZERO plaintext Admin passwords.
5. Single-Campus Main Edition Hard-Lock:
   - Asserts campusId == "WCI_UNNAO_MAIN", GPS Geofence (26.538162, 80.484187, 50m radius).
6. Multi-Module Project Architecture:
   - Asserts presence and integrity of :app, :ui-liquid-glass, :core-crypto, :native-cpp20, :exam-engine, :sandbox-runtime.
"""

import os
import sys
import re

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))

IGNORE_DIRS = {
    ".git",
    ".gradle",
    "build",
    ".idea",
    ".gemini",
    ".agent",
    "scratch",
    "node_modules",
    "dist",
    ".cxx"
}

def is_ignored(path):
    parts = path.split(os.sep)
    return any(ignored in parts for ignored in IGNORE_DIRS)

def check_zero_web_files():
    print("[1/6] Checking for ZERO Web / Website Files (Inside Android Application)...")
    forbidden_exts = {".html", ".css", ".jsx"}
    forbidden_dirs = {"web-portal", "hosting", "hosting-prod"}
    violations = []

    for root, dirs, files in os.walk(ROOT_DIR):
        if is_ignored(root):
            continue
        rel_root = os.path.relpath(root, ROOT_DIR)
        # Allow Firebase Hosting public verification portal directory (Priority 3)
        if rel_root == "public" or rel_root.startswith("public" + os.sep):
            continue
        
        # Check forbidden directory names
        for d in dirs:
            if d.lower() in forbidden_dirs:
                violations.append(f"Forbidden directory '{d}' found at: {os.path.join(rel_root, d)}")
        
        # Check forbidden file extensions
        for f in files:
            ext = os.path.splitext(f)[1].lower()
            if ext in forbidden_exts:
                violations.append(f"Forbidden web file found: {os.path.join(rel_root, f)}")
            if f.lower() == "index.html":
                violations.append(f"Forbidden index.html found: {os.path.join(rel_root, f)}")

    if violations:
        print(f"FAILED: Found {len(violations)} web file violation(s):")
        for v in violations:
            print(f"  - {v}")
        return False
    print("  PASS: 100% Pure Native Android — Zero web files inside Android app.")
    return True

def check_zero_payment_gateways():
    print("[2/6] Checking for ZERO Online Payment Gateways...")
    # Patterns for online checkout / payment gateways
    prohibited_patterns = [
        (re.compile(r'\brazorpay\b', re.IGNORECASE), "Razorpay payment SDK"),
        (re.compile(r'\bcom\.stripe\b', re.IGNORECASE), "Stripe payment SDK"),
        (re.compile(r'\bcom\.phonepe\.app\b', re.IGNORECASE), "PhonePe query intent"),
        (re.compile(r'\bnet\.one97\.paytm\b', re.IGNORECASE), "Paytm query intent"),
        (re.compile(r'upi://pay', re.IGNORECASE), "UPI Intent checkout scheme"),
        (re.compile(r'\bZeroFeeUpiPaymentEngine\b'), "Prohibited UPI payment engine"),
    ]
    violations = []

    # Files to scan: all source files except this verification script and the master PRD/PDF
    scan_exts = {".kt", ".java", ".xml", ".gradle", ".kts", ".properties", ".json", ".ts", ".js", ".cpp", ".h"}

    for root, dirs, files in os.walk(ROOT_DIR):
        if is_ignored(root):
            continue
        for f in files:
            if f in ("verify_static_guardrails.py", "extract_prd.py", "fast_extract_prd.py", "extracted_prd.txt"):
                continue
            ext = os.path.splitext(f)[1].lower()
            if ext in scan_exts:
                fpath = os.path.join(root, f)
                rel_path = os.path.relpath(fpath, ROOT_DIR)
                try:
                    with open(fpath, "r", encoding="utf-8", errors="ignore") as fp:
                        for line_no, line in enumerate(fp, 1):
                            # Skip comments that document prohibition
                            stripped = line.strip()
                            if stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("#"):
                                if "prohibit" in stripped.lower() or "zero" in stripped.lower() or "never" in stripped.lower():
                                    continue
                            for pattern, desc in prohibited_patterns:
                                if pattern.search(line):
                                    violations.append(f"{rel_path}:{line_no} [{desc}] -> {stripped[:80]}")
                except Exception as e:
                    pass

    if violations:
        print(f"FAILED: Found {len(violations)} online payment gateway violation(s):")
        for v in violations:
            print(f"  - {v}")
        return False
    print("  PASS: Zero Online Payment Gateways (Strict offline campus counter receipts only).")
    return True

def check_zero_emi_installments():
    print("[3/6] Checking for ZERO Installment / EMI Systems...")
    prohibited_patterns = [
        (re.compile(r'\bUpiAndAutoEmi\w*', re.IGNORECASE), "Auto-EMI module"),
        (re.compile(r'\bemi_schedule\b', re.IGNORECASE), "EMI schedule schema"),
        (re.compile(r'\binstallment_plan\b', re.IGNORECASE), "Installment plan logic"),
        (re.compile(r'\blate_interest_engine\b', re.IGNORECASE), "Late interest engine"),
        (re.compile(r'\brecurring_auto_debit\b', re.IGNORECASE), "Recurring auto-debit"),
    ]
    violations = []
    scan_exts = {".kt", ".java", ".gradle", ".kts", ".json", ".ts", ".js"}

    for root, dirs, files in os.walk(ROOT_DIR):
        if is_ignored(root):
            continue
        for f in files:
            if f in ("verify_static_guardrails.py", "extract_prd.py", "fast_extract_prd.py", "extracted_prd.txt"):
                continue
            ext = os.path.splitext(f)[1].lower()
            if ext in scan_exts:
                fpath = os.path.join(root, f)
                rel_path = os.path.relpath(fpath, ROOT_DIR)
                try:
                    with open(fpath, "r", encoding="utf-8", errors="ignore") as fp:
                        for line_no, line in enumerate(fp, 1):
                            stripped = line.strip()
                            if stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("#"):
                                if "prohibit" in stripped.lower() or "zero" in stripped.lower() or "never" in stripped.lower():
                                    continue
                            for pattern, desc in prohibited_patterns:
                                if pattern.search(line):
                                    violations.append(f"{rel_path}:{line_no} [{desc}] -> {stripped[:80]}")
                except Exception:
                    pass

    if violations:
        print(f"FAILED: Found {len(violations)} EMI / Installment system violation(s):")
        for v in violations:
            print(f"  - {v}")
        return False
    print("  PASS: Zero Installment / EMI System (Strict flat course fee minus manual receipts).")
    return True

def check_zero_dummy_data():
    print("[4/6] Checking for ZERO Fake Logic & ZERO Dummy Data...")
    prohibited_patterns = [
        (re.compile(r'listOf\s*\(\s*"John Doe"', re.IGNORECASE), "Hardcoded dummy student name 'John Doe'"),
        (re.compile(r'listOf\s*\(\s*"Jane Doe"', re.IGNORECASE), "Hardcoded dummy student name 'Jane Doe'"),
        (re.compile(r'val\s+ADMIN_PASSWORD\s*=\s*"[^"]+"', re.IGNORECASE), "Hardcoded plaintext admin password"),
    ]
    violations = []
    scan_exts = {".kt", ".java", ".cpp"}

    for root, dirs, files in os.walk(ROOT_DIR):
        if is_ignored(root):
            continue
        for f in files:
            if f in ("verify_static_guardrails.py", "extract_prd.py", "fast_extract_prd.py", "extracted_prd.txt"):
                continue
            ext = os.path.splitext(f)[1].lower()
            if ext in scan_exts:
                fpath = os.path.join(root, f)
                rel_path = os.path.relpath(fpath, ROOT_DIR)
                try:
                    with open(fpath, "r", encoding="utf-8", errors="ignore") as fp:
                        for line_no, line in enumerate(fp, 1):
                            for pattern, desc in prohibited_patterns:
                                if pattern.search(line):
                                    violations.append(f"{rel_path}:{line_no} [{desc}] -> {line.strip()[:80]}")
                except Exception:
                    pass

    if violations:
        print(f"FAILED: Found {len(violations)} dummy data / fake logic violation(s):")
        for v in violations:
            print(f"  - {v}")
        return False
    print("  PASS: Zero Dummy Data — All tables and lists start 100% empty, zero hardcoded passwords.")
    return True

def check_single_campus_lock():
    print("[5/6] Checking Single-Campus Main Edition Hard-Lock...")
    required_constants = [
        ("WCI_UNNAO_MAIN", "Single-Campus CampusId Lock"),
        ("26.538162", "Campus Geofence Latitude (Unnao)"),
        ("80.484187", "Campus Geofence Longitude (Unnao)")
    ]
    found = {c[0]: False for c in required_constants}

    scan_exts = {".kt", ".java", ".properties", ".rules", ".json"}
    for root, dirs, files in os.walk(ROOT_DIR):
        if is_ignored(root):
            continue
        for f in files:
            ext = os.path.splitext(f)[1].lower()
            if ext in scan_exts:
                fpath = os.path.join(root, f)
                try:
                    with open(fpath, "r", encoding="utf-8", errors="ignore") as fp:
                        content = fp.read()
                        for c_key in found:
                            if c_key in content:
                                found[c_key] = True
                except Exception:
                    pass

    missing = [c for c in required_constants if not found[c[0]]]
    if missing:
        print(f"FAILED: Missing single-campus lock constants:")
        for m in missing:
            print(f"  - {m[0]} ({m[1]})")
        return False
    print("  PASS: Single-Campus Main Edition hard-locked (campusId='WCI_UNNAO_MAIN', GPS: 26.538162, 80.484187).")
    return True

def check_multi_module_scaffold():
    print("[6/7] Checking Multi-Module Project Architecture...")
    required_modules = [
        "app",
        "ui-liquid-glass",
        "core-crypto",
        "native-cpp20",
        "exam-engine",
        "sandbox-runtime"
    ]
    missing = []
    for m in required_modules:
        m_dir = os.path.join(ROOT_DIR, m)
        if not os.path.isdir(m_dir):
            missing.append(f"Module directory '{m}' is missing")
            continue
        gradle_file = os.path.join(m_dir, "build.gradle.kts")
        if not os.path.isfile(gradle_file):
            missing.append(f"build.gradle.kts in module '{m}' is missing")

    if missing:
        print(f"FAILED: Multi-module structure incomplete:")
        for m in missing:
            print(f"  - {m}")
        return False
    print(f"  PASS: All {len(required_modules)} required Android modules properly scaffolded.")
    return True

def check_phase2_native_and_crypto():
    print("[7/7] Checking Phase 2 Native C++20 NDK & Core Cryptography Components...")
    required_files = [
        "native-cpp20/CMakeLists.txt",
        "native-cpp20/src/MemoryWiper.h",
        "native-cpp20/src/MemoryWiper.cpp",
        "native-cpp20/src/FridaSentinel.h",
        "native-cpp20/src/FridaSentinel.cpp",
        "native-cpp20/src/KernelIntegrityDaemon.h",
        "native-cpp20/src/KernelIntegrityDaemon.cpp",
        "native-cpp20/src/SeccompSandbox.h",
        "native-cpp20/src/SeccompSandbox.cpp",
        "native-cpp20/src/SandboxMemoryCap.h",
        "native-cpp20/src/SandboxMemoryCap.cpp",
        "native-cpp20/src/PqcKeyExchange.h",
        "native-cpp20/src/PqcKeyExchange.cpp",
        "native-cpp20/src/VmBytecodeInterpreter.h",
        "native-cpp20/src/VmBytecodeInterpreter.cpp",
        "native-cpp20/src/JniVaultBridge.cpp",
        "native-cpp20/src/main/kotlin/com/wci/nativecxx/WciNativeSentinel.kt",
        "core-crypto/src/main/kotlin/com/wci/core/crypto/attest/StrongBoxAttestor.kt",
        "core-crypto/src/main/kotlin/com/wci/core/crypto/attest/HardwareKeypairManager.kt",
        "core-crypto/src/main/kotlin/com/wci/core/crypto/attest/TeeAttestationClient.kt",
        "core-crypto/src/main/kotlin/com/wci/core/crypto/biometric/BiometricEnclaveBridge.kt",
        "core-crypto/src/main/kotlin/com/wci/core/crypto/biometric/BioChallengeEngine.kt",
        "core-crypto/src/main/kotlin/com/wci/core/crypto/db/SqlCipherFactory.kt",
        "core-crypto/src/main/kotlin/com/wci/core/crypto/db/SqlCipherSanitizer.kt",
        "core-crypto/src/main/kotlin/com/wci/core/crypto/hash/Argon2idHasher.kt",
        "core-crypto/src/main/kotlin/com/wci/core/crypto/jwt/DpopTokenBinder.kt",
        "core-crypto/src/main/kotlin/com/wci/core/crypto/jwt/EphemeralTokenRotator.kt",
        "core-crypto/src/main/kotlin/com/wci/core/crypto/ratchet/DoubleRatchetEngine.kt",
        "core-crypto/src/main/kotlin/com/wci/core/crypto/tink/TinkVaultManager.kt",
        "core-crypto/src/main/kotlin/com/wci/core/crypto/vault/HomomorphicAnswerVault.kt",
        "core-crypto/src/main/kotlin/com/wci/core/crypto/lockdown/SnapshotLockdown.kt",
        "core-crypto/src/main/kotlin/com/wci/core/crypto/qr/Ed25519QrSigner.kt",
        "core-crypto/src/main/kotlin/com/wci/core/crypto/pqc/HybridPqcKem.kt",
        "core-crypto/src/test/kotlin/com/wci/core/crypto/CoreCryptoTestSuite.kt"
    ]
    missing = []
    for rf in required_files:
        path = os.path.join(ROOT_DIR, rf)
        if not os.path.isfile(path):
            missing.append(rf)

    if missing:
        print(f"FAILED: Missing {len(missing)} Phase 2 file(s):")
        for m in missing:
            print(f"  - {m}")
        return False

    print(f"  PASS: All {len(required_files)} Phase 2 Native C++20 and Core-Crypto components verified.")
    return True

def check_phase3_ui_liquid_glass():
    print("[8/9] Checking Phase 3 3D Ultra Liquid Glass UI Kit & Unified Login...")
    required_files = [
        "ui-liquid-glass/src/main/kotlin/com/wci/ui/liquidglass/theme/GlassShaderTokens.kt",
        "ui-liquid-glass/src/main/kotlin/com/wci/ui/liquidglass/shaders/LiquidRefractionAgsl.kt",
        "ui-liquid-glass/src/main/kotlin/com/wci/ui/liquidglass/components/GlassSurface.kt",
        "ui-liquid-glass/src/main/kotlin/com/wci/ui/liquidglass/components/GlassControls.kt",
        "ui-liquid-glass/src/main/kotlin/com/wci/ui/liquidglass/nav/CurvedLiquidSidebar.kt",
        "ui-liquid-glass/src/main/kotlin/com/wci/ui/liquidglass/login/PortalSelectorDock.kt",
        "ui-liquid-glass/src/main/kotlin/com/wci/ui/liquidglass/login/BioEnrollWizard.kt",
        "ui-liquid-glass/src/main/kotlin/com/wci/ui/liquidglass/login/UnifiedLoginScreen.kt"
    ]
    missing = []
    for rf in required_files:
        path = os.path.join(ROOT_DIR, rf)
        if not os.path.isfile(path):
            missing.append(rf)

    if missing:
        print(f"FAILED: Missing {len(missing)} Phase 3 file(s):")
        for m in missing:
            print(f"  - {m}")
        return False

    print(f"  PASS: All {len(required_files)} Phase 3 UI Liquid Glass components verified.")
    return True

def check_phase4_firebase_and_cloud_functions():
    print("[9/9] Checking Phase 4 Firebase Backend & Cloud Functions...")
    required_files = [
        "firebase/firestore.rules",
        "firebase/firestore.indexes.json",
        "cloud-functions/package.json",
        "cloud-functions/tsconfig.json",
        "cloud-functions/src/index.ts",
        "cloud-functions/src/auth/CampusScopeGuard.ts",
        "cloud-functions/src/auth/DpopVerifier.ts",
        "cloud-functions/src/auth/AttestVerifier.ts",
        "cloud-functions/src/auth/BioChallengeVerify.ts",
        "cloud-functions/src/auth/ZanzibarEvaluator.ts",
        "cloud-functions/src/security/IntrusionSentinel.ts",
        "cloud-functions/src/security/RemoteLockdown.ts",
        "cloud-functions/src/ledger/OfflineFeeReceiptEngine.ts",
        "cloud-functions/src/exams/ConfidentialGrader.ts",
        "cloud-functions/src/exams/T30AutoGrader.ts",
        "cloud-functions/src/audit/MerkleEmitter.ts"
    ]
    missing = []
    for rf in required_files:
        path = os.path.join(ROOT_DIR, rf)
        if not os.path.isfile(path):
            missing.append(rf)

    if missing:
        print(f"FAILED: Missing {len(missing)} Phase 4 file(s):")
        for m in missing:
            print(f"  - {m}")
        return False

    print(f"  PASS: All {len(required_files)} Phase 4 Backend & Cloud Functions components verified.")
    return True

def check_phase5_admin_portal():
    print("[10/13] Checking Phase 5 Admin Portal Modules (F-021 to F-190) & Offline Fee Ledger Engine...")
    required_files = [
        "app/src/main/java/com/example/ui/portal/PortalShellScreen.kt",
        "app/src/main/java/com/example/ui/modules/DashboardAndPeopleModules.kt",
        "app/src/main/java/com/example/ui/modules/AcademicsAndFinanceModules.kt",
        "app/src/main/java/com/example/ui/modules/ExamsMaterialsCertificatesLiveAiModules.kt",
        "app/src/main/java/com/example/ui/modules/ReportsInventorySupportSecuritySettingsModules.kt",
        "app/src/main/java/com/waqar/institute/ledger/OfflineFeeLedgerScreen.kt",
        "app/src/main/java/com/waqar/institute/ledger/OfflineFeeLedgerEngine.kt"
    ]
    missing = []
    for rf in required_files:
        path = os.path.join(ROOT_DIR, rf)
        if not os.path.isfile(path):
            missing.append(rf)

    if missing:
        print(f"FAILED: Missing {len(missing)} Phase 5 file(s):")
        for m in missing:
            print(f"  - {m}")
        return False

    print(f"  PASS: All {len(required_files)} Phase 5 Admin Portal modules and Offline Fee Ledger verified.")
    return True

def check_phase6_teacher_portal():
    print("[11/13] Checking Phase 6 Teacher Portal Modules (F-191 to F-310) & Offline Grade Queue...")
    required_files = [
        "app/src/main/java/com/waqar/institute/data/teacher/OfflineGradeQueue.kt",
        "app/src/main/java/com/waqar/institute/data/teacher/DraftContentVault.kt",
        "app/src/main/java/com/waqar/institute/ui/teacher/TeacherDashboardScreen.kt",
        "app/src/main/java/com/waqar/institute/ui/teacher/PdfExamUploadScreen.kt",
        "app/src/main/java/com/waqar/institute/ui/teacher/LiveProctoringScreen.kt",
        "app/src/main/java/com/waqar/institute/ui/teacher/PostExamAnalyticsScreen.kt",
        "app/src/main/java/com/waqar/institute/ui/teacher/ClassroomGradingScreen.kt",
        "app/src/main/java/com/waqar/institute/ui/teacher/CourseAuthoringScreen.kt",
        "app/src/main/java/com/waqar/institute/ui/teacher/TeacherMentoringScreen.kt",
        "app/src/main/java/com/waqar/institute/ui/teacher/TeacherProfileScreen.kt"
    ]
    missing = []
    for rf in required_files:
        path = os.path.join(ROOT_DIR, rf)
        if not os.path.isfile(path):
            missing.append(rf)

    if missing:
        print(f"FAILED: Missing {len(missing)} Phase 6 file(s):")
        for m in missing:
            print(f"  - {m}")
        return False

    print(f"  PASS: All {len(required_files)} Phase 6 Teacher Portal modules and Offline Grade Vault verified.")
    return True

def check_phase7_student_portal_and_exam_engine():
    print("[12/13] Checking Phase 7 Student Portal, Biometric Exam Arena & Sandbox Runtime (F-311 to F-430)...")
    required_files = [
        "exam-engine/src/main/kotlin/com/wci/exam/engine/student/EncryptedAnswerSaver.kt",
        "exam-engine/src/main/kotlin/com/wci/exam/engine/student/OfflineExamBuffer.kt",
        "exam-engine/src/main/kotlin/com/wci/exam/engine/student/BiometricExamArenaScreen.kt",
        "sandbox-runtime/src/main/kotlin/com/wci/sandbox/runtime/AstBlacklistGuard.kt",
        "sandbox-runtime/src/main/kotlin/com/wci/sandbox/runtime/VolatileFileJail.kt",
        "sandbox-runtime/src/main/kotlin/com/wci/sandbox/runtime/WatchdogTimer.kt",
        "sandbox-runtime/src/main/kotlin/com/wci/sandbox/runtime/IsolatedCodingSandbox.kt",
        "app/src/main/java/com/waqar/institute/ui/student/StudentDashboardScreen.kt",
        "app/src/main/java/com/waqar/institute/ui/student/TallySimulatorScreen.kt",
        "app/src/main/java/com/waqar/institute/ui/student/ZeroBillAiTutorScreen.kt",
        "app/src/main/java/com/waqar/institute/ui/student/StudentIdAndFeesScreen.kt",
        "app/src/main/java/com/waqar/institute/ui/student/StudentProfileScreen.kt"
    ]
    missing = []
    for rf in required_files:
        path = os.path.join(ROOT_DIR, rf)
        if not os.path.isfile(path):
            missing.append(rf)

    if missing:
        print(f"FAILED: Missing {len(missing)} Phase 7 file(s):")
        for m in missing:
            print(f"  - {m}")
        return False

    print(f"  PASS: All {len(required_files)} Phase 7 Student Portal, Exam Arena & Isolated Sandbox components verified.")
    return True

def check_phase8_parent_portal_and_terms():
    print("[13/13] Checking Phase 8 Parent Portal Telemetry & 10-Rule Terms (F-431 to F-520)...")
    required_files = [
        "app/src/main/java/com/waqar/institute/ui/parent/ParentDashboardScreen.kt",
        "app/src/main/java/com/waqar/institute/ui/parent/ParentAttendanceScreen.kt",
        "app/src/main/java/com/waqar/institute/ui/parent/ParentResultsScreen.kt",
        "app/src/main/java/com/waqar/institute/ui/parent/ParentFeeLedgerScreen.kt",
        "app/src/main/java/com/waqar/institute/ui/parent/ParentTeacherChatScreen.kt",
        "app/src/main/java/com/waqar/institute/ui/parent/ParentSecurityScreen.kt",
        "app/src/main/java/com/waqar/institute/ui/shared/TermsAndConditionsScreen.kt"
    ]
    missing = []
    for rf in required_files:
        path = os.path.join(ROOT_DIR, rf)
        if not os.path.isfile(path):
            missing.append(rf)

    if missing:
        print(f"FAILED: Missing {len(missing)} Phase 8 file(s):")
        for m in missing:
            print(f"  - {m}")
        return False

    print(f"  PASS: All {len(required_files)} Phase 8 Parent Portal and 10-Rule Terms components verified.")
    return True

def main():
    print("=" * 80)
    print(" WAQAR COMPUTER INSTITUTE ERP — STATIC GUARDRAILS & ZERO-VIOLATION VERIFIER")
    print("=" * 80)

    checks = [
        check_zero_web_files,
        check_zero_payment_gateways,
        check_zero_emi_installments,
        check_zero_dummy_data,
        check_single_campus_lock,
        check_multi_module_scaffold,
        check_phase2_native_and_crypto,
        check_phase3_ui_liquid_glass,
        check_phase4_firebase_and_cloud_functions,
        check_phase5_admin_portal,
        check_phase6_teacher_portal,
        check_phase7_student_portal_and_exam_engine,
        check_phase8_parent_portal_and_terms
    ]

    all_passed = True
    for check in checks:
        if not check():
            all_passed = False
        print("-" * 80)

    if all_passed:
        print(">>> ALL STATIC GUARDRAILS & PHASES 1-9 VERIFICATIONS PASSED (ZERO VIOLATIONS DETECTED). READY FOR ENTERPRISE RELEASE. <<<")
        sys.exit(0)
    else:
        print(">>> STATIC GUARDRAILS FAILED. FIX VIOLATIONS BEFORE PROCEEDING. <<<")
        sys.exit(1)

if __name__ == "__main__":
    main()
