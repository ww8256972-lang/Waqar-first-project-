# Waqar Computer Institute ERP — Ultra-Professional Academic Suite, Maha Brahmastra Pro+ Security & 120Hz Biometric Upgrade Plan

This plan delivers four production-grade Computer Institute ERP capabilities (without any payment gateway), layers five next-generation **Maha Brahmastra Pro+ (#6–#10)** security defenses on top of your existing **#1–#5** security rules, upgrades the **128-D Face Biometric** pipeline across Admin, Teacher, and Exam workflows, and tunes rendering and local database pipelines for smooth **120Hz** performance—while preserving 100% of existing modules, data, and the instant `<0.1s` Admin (`waqar`) login.

---

## User Review & Critical Decisions

> [!IMPORTANT]
> All choices confirmed in your questionnaire are locked into this plan, and **zero existing features, screens, Firestore collections, Room tables, or security rules (#1–#5) will be removed or weakened**.

- **Confirmed Feature Scope (4 New Modules, Zero Payment Gateway)**:
  1. **QR + Face Dual-Factor Smart Attendance Scanner** (Instant ID QR scan + 0.5s 128-D live face verification with offline SQLCipher queue and Cloud Firestore sync).
  2. **Computer Lab PC Allotment & Syllabus Topic Tracker** (Interactive `PC-01` to `PC-50` seat map, hardware status tracking, student seat binding, and batch-wise syllabus milestone progress `%`).
  3. **PDF ID Card & Exam Admit Card Studio** (Native Android `PdfDocument` generator producing print-ready PVC Student/Staff ID cards and Exam Hall Tickets with cryptographic QR verification).
  4. **Typing Speed & Accuracy Test Arena (Hindi & English WPM)** (Live WPM, Net Speed, Accuracy `%`, Backspace counter, and exam-grade timer for DCA/ADCA/CCC students).
- **Confirmed Maha Brahmastra Pro+ Security Rules (#6–#10 Added Above Existing #1–#5)**:
  - **Pro+ #6 & #9**: Native `/proc/self/maps` executable memory integrity scanner + Canary Honeypot Trap Vault (`CRITICAL_HONEYPOT_TRIGGERED` alert on unauthorized memory/storage probing).
  - **Pro+ #7**: Anti-Overlay, Tapjacking, Screen-Share & Remote-Control Exam Blocker (`filterTouchesWhenObscured` + active Accessibility/VirtualDisplay audit during CBT exams).
  - **Pro+ #8 & #10**: Hardware Keystore Row-Level HMAC + Per-Request Anti-Replay Nonce & Monotonic Counter Envelope on sensitive mutations.
- **Confirmed Face Biometric & 120Hz Smoothness Architecture**:
  - **Admin Step-Up Face Lock on Critical Actions Only**: Normal `waqar` Admin login remains `<0.1s` offline/online via password; destructive/high-privilege actions (Student Delete, Admin Credential Rotation, Security Account Lock) trigger a 1-second step-up face check against the Admin Face Vault.
  - **Live Exam Multi-Face & Continuous Presence Check**: Periodic non-intrusive front-camera checks during active CBT exams that flag missing candidates (`0 faces`) or helper cheating (`>1 face`).
  - **Teacher Multi-Angle Liveness (Blink + Head Turn)**: Combines eye-blink + head-turn yaw verification with the 50m campus GPS geofence.

---

## 1. Overview & Core Concept

- **What It Does**: Expands **Waqar Computer Institute ERP** with hands-on computer lab operations (PC seat allocation, Hindi/English typing tests, print-ready PDF ID/Admit cards, and QR + Face dual-factor attendance) fortified by hardware-backed anti-tamper defenses and multi-stage biometric liveness.
- **Target Audience / Persona**:
  - **Institute Director / Admin (`waqar`)**: Manages students, faculty, computer lab PCs, print-ready ID/Admit cards, and real-time security telemetry with instant `<0.1s` login and step-up face protection on destructive actions.
  - **Faculty / Teachers**: Marks verified campus attendance via 50m GPS + Blink/Head-Turn liveness, tracks batch-wise syllabus completion, and monitors lab seats.
  - **Students & Parents**: Practices Hindi/English typing with live WPM analytics, downloads official PDF ID/Admit cards, takes proctored CBT exams with multi-face anti-cheat protection, and views real-time syllabus progress.
- **Key Value**: Every feature runs on real native Android APIs (`PdfDocument`, `CameraX`, `ML Kit / 128-D Face Engine`, `AndroidKeyStore`, `SQLCipher WAL`, and `Cloud Firestore`) with zero mock stubs and zero dependency on external paid gateways.

---

## 2. User Experience & Visual Design

- **Key User Flows**:
  1. **QR + Face Dual-Factor Attendance Flow**: Teacher/Admin opens the Smart Scanner -> points camera at Student ID QR -> student enrollment is resolved in `<10ms` -> same CameraX preview verifies the student's 128-D face vector in `<0.5s` -> marks `PRESENT (QR+FACE)` in SQLCipher + Firestore.
  2. **Computer Lab PC & Syllabus Tracker Flow**: Admin/Teacher selects a Batch -> views visual Lab Grid (`PC-01`..`PC-50` with color-coded `ASSIGNED`, `AVAILABLE`, `MAINTENANCE` badges) -> assigns a student to a PC or toggles completed syllabus topics -> progress bar (`%`) updates immediately across Student/Parent portals.
  3. **PDF ID Card & Admit Card Studio Flow**: Admin/Student selects a Student profile -> taps *Generate PVC ID Card* or *Generate Exam Admit Card* -> previews high-DPI card with portrait, roll number, batch, and signed QR code -> saves/shares print-ready PDF via Android Share Sheet.
  4. **Typing Speed Arena Flow**: Student/Teacher chooses **English (QWERTY)** or **Hindi (Mangal Unicode / KrutiDev Practice)** passage and timer (`1m`, `3m`, `5m`) -> types in real time with highlighted word cursor, live Gross WPM, Net WPM, Accuracy `%`, and Backspace count -> saves verified score to cloud & local leaderboard.
- **Visual Identity & Theme**:
  - **Preserved Royal Obsidian & Cyber Indigo Glassmorphism**: Retains the existing dark/light theme tokens (`MaterialTheme.colorScheme`), neon indigo/emerald status badges, and clean 8.dp Material 3 spacing grid.
  - **Typography & Hierarchy**: Tabular monospace numerals for WPM counters, exam countdowns, and cryptographic hashes; clear bold headers for lab seat cards and syllabus milestones.
- **Interactive Feedback & Motion**:
  - Physics-based spring transitions (`spring(stiffness = Spring.StiffnessMediumLow)`) under 250ms, hardware-accelerated Canvas overlays for the QR + Face scanner viewfinder, and instant tactile ripple feedback on all 48.dp+ touch targets.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Native Android `PdfDocument` Engine for ID Cards & Admit Cards**
  - *Chosen Approach*: Render vector-sharp PDFs directly on-device using `android.graphics.pdf.PdfDocument` +ZXing/Canvas QR matrices.
  - *Why*: Works 100% offline and online in `<80ms`, requires zero paid external PDF APIs, and produces crisp 300-DPI print-ready cards.
- **Decision 2: Step-Up Biometric Gate on Destructive Admin Actions Only**
  - *Chosen Approach*: Keep Admin portal login (`waqar`) instant (`<0.1s`) via Keystore SHA-256 digest, and invoke the Admin Master Face check only when executing critical destructive operations.
  - *Why*: Preserves rapid daily Admin productivity while guaranteeing that an unattended unlocked device cannot be used to delete students or rotate master credentials.
- **Decision 3: Layered Maha Brahmastra Pro+ (#6–#10) Without Touching Existing Rules (#1–#5)**
  - *Chosen Approach*: Implement Rules #6–#10 as additive, modular guards inside the security package and JNI bridge while leaving existing cert-pinning, DEX CRC32, HMAC exam vault, and rate-limiter logic untouched.
  - *Why*: Guarantees zero regression on existing unit/Robolectric tests and CI workflows.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

### Architecture & Component Diagram

```
┌───────────────────────────────────────────────────────────────────────────────┐
│                   WAQAR COMPUTER INSTITUTE ERP (JETPACK COMPOSE)              │
├──────────────────┬──────────────────┬───────────────────┬─────────────────────┤
│  QR + Face Dual  │ Computer Lab PC  │  PDF ID & Admit   │  Hindi / English    │
│  Scanner Studio  │ & Syllabus Track │   Card Studio     │  Typing Test Arena  │
└────────┬─────────┴────────┬─────────┴─────────┬─────────┴──────────┬──────────┘
         │                  │                   │                    │
         ▼                  ▼                   ▼                    ▼
┌───────────────────────────────────────────────────────────────────────────────┐
│              MAHA BRAHMASTRA PRO+ SECURITY & BIOMETRIC LAYER                  │
│  • Rules #1–#5 (Preserved: Cert/DEX Pin, HMAC Exam Vault, 5-in-60s Limiter)   │
│  • Pro+ #6: Native /proc/self/maps Executable Segment Checksum Verifier       │
│  • Pro+ #7: Anti-Overlay / Screen-Share / Accessibility Exam Blocker          │
│  • Pro+ #8: Per-Request Cryptographic Nonce + Monotonic Counter Envelope      │
│  • Pro+ #9: Canary Honeypot Trap Vault (Intrusion Telemetry Alert)            │
│  • Pro+ #10: Hardware Keystore Row-Level HMAC Integrity Verifier              │
│  • Biometrics: Teacher Blink+Yaw | Exam Multi-Face Check | Admin Step-Up Gate │
└──────────────────────────────────────┬────────────────────────────────────────┘
                                       │
         ┌─────────────────────────────┴─────────────────────────────┐
         ▼                                                           ▼
┌──────────────────────────────────────┐   ┌────────────────────────────────────┐
│  SQLCipher 256-Bit Room DB (WAL)     │   │  Cloud Firestore (Zero-Trust RBAC) │
│  • Indexed Paging 3 + @Immutable UI  │   │  • Students, Batches, Attendance   │
│  • Offline Attendance & Lab Cache    │   │  • Lab PCs, Syllabus, Typing, Logs │
└──────────────────────────────────────┘   └────────────────────────────────────┘
```

### Data Model & State Strategy

- **QR + Face Dual Attendance**: Uses existing `AttendanceRecord` (`method = "QR_PLUS_FACE_BIOMETRIC"`) with cryptographic QR payload verification (`WCI-ID|<studentId>|<enrollmentNo>|<hmac16>`) + 128-D cosine similarity (`>= 0.80f`).
- **Computer Lab PC Allotment & Syllabus Tracker**: Stored in `InstituteBackupOrConfig` / dedicated structured JSON & Firestore documents (`category = "LAB_PC_ALLOTMENT"` and `category = "SYLLABUS_TRACKER"`) plus in-memory reactive `StateFlow` in `InstituteViewModel`, ensuring zero destructive Room schema changes while syncing across Admin, Teacher, Student, and Parent portals.
- **Typing Speed & Accuracy Test Arena**: Calculates live `grossWpm = (allTypedChars / 5.0) / elapsedMinutes`, `netWpm = max(0, grossWpm - (uncorrectedErrors / elapsedMinutes))`, and `accuracyPercent = (correctChars * 100) / max(1, totalChars)`, recording completed attempts into `ExamResult` (`examType = "TYPING_ARENA"`) and Student performance analytics.
- **Maha Brahmastra Pro+ (#6–#10) & Biometric Gates**:
  - `MahaBrahmastraProPlusEngine`: Encapsulates Rule #6 (`/proc/self/maps` executable segment integrity), Rule #7 (Overlay/Accessibility/VirtualDisplay display inspection), Rule #8 (One-Time Nonce + AtomicLong monotonic counter + Keystore HMAC), Rule #9 (Canary Honeypot key-value trap), and Rule #10 (Keystore Row-Level HMAC seal for fee/exam/attendance records).
  - `MultiStageBiometricCoordinator`: Provides Teacher `Blink + Head-Turn` challenge verification, Student Exam `0-face / Multi-face (>1 face)` frame audit, and Admin Step-Up Face Gate dialog state before critical actions.
  - **120Hz Smoothness**: Enables SQLCipher `ENABLE_WRITE_AHEAD_LOGGING`, adds `androidx.profileinstaller` startup warming, and configures Coil singleton `ImageLoader` with hardware bitmaps and memory/disk caches.
