# WAQAR COMPUTER INSTITUTE — UI/UX PRD & SYSTEM ARCHITECTURE SPECIFICATION

**Document Version:** 2.0 (Post-Audit Master Release)  
**Target Platform:** Native Android (Kotlin + Jetpack Compose)  
**System Architecture:** Native MVVM + Clean Architecture + Firebase Cloud Backend  
**Document Status:** Complete UI Product Requirement Document (PRD), Information Architecture & Motion Specification  

---

## 1. Executive Summary & Critical UI Audit

This Product Requirement Document (PRD) delivers an end-to-end user interface architecture, navigation design, and visual motion specification for the **Waqar Computer Institute** native Android application. 

A thorough technical audit of the project source files (`app/src/main/java/com/example/ui/`) extracted from `waqar-institute.zip` revealed critical architectural defects, navigation misalignment, UI text inconsistencies, and improper material assignments. Below is the itemized diagnostic of issues identified in the codebase and their architectural remediations.

### 1.1 Critical Material Assignment Flaws (Code vs. Requirement)

The application mandates four distinct visual design systems tailored to the psychology and daily usage patterns of each user role:
- **Teacher Portal**: **Glassmorphism (Frosted Specular Glass)**. Designed for fast classroom operations, high readability under fluctuating light conditions, minimal visual clutter, and instant tap targets.
- **Student Portal**: **Claymorphism (3D Tactile Soft Surfaces)**. Designed for an engaging, encouraging, playful learning environment with soft dual-bevel shadows and puffy touchable cards.
- **Parent Portal**: **Liquid Glass (Fluid Refraction & Reassurance)**. Designed for calm, reassuring family monitoring with pristine read-only clarity and soft specular ribbons.
- **Admin Portal**: **Liquid Glass (Multi-Layer Specular Glass & Neon Borders)**. Designed as a high-density, executive command center with dynamic specular reflections, ambient auroral glow, and high-contrast numerical tickers.

#### The Codebase Bug:
In `app/src/main/java/com/example/ui/designsystem/Materials.kt` (lines 43–45) and `PortalShellScreen.kt` (lines 142, 268, 590, 725), the previous developer implemented:
```kotlin
// INCORRECT CODEBASE IMPLEMENTATION:
val isClayPortal = portalRole == PortalRole.STUDENT || portalRole == PortalRole.PARENT
```
This conflated the **Parent Portal** into Claymorphism and left the **Teacher Portal** ambiguously assigned to Liquid Glass. 

#### The Architectural Fix:
The design system must explicitly evaluate all four distinct material pipelines independently:
```kotlin
// CORRECT ARCHITECTURAL IMPLEMENTATION:
val isGlassPortal = portalRole == PortalRole.TEACHER       // Glassmorphism (Frosted, 1dp specular rim)
val isClayPortal = portalRole == PortalRole.STUDENT        // Claymorphism (3D Puffy Dual-Bevel)
val isLiquidPortal = portalRole == PortalRole.PARENT || portalRole == PortalRole.ADMIN // Liquid Glass
```


*Figure 1.1: The Four Design Materials — Glassmorphism, Liquid Glass, Claymorphism, and Neon Accents.*

---

### 1.2 Navigation Shell & Information Architecture Misalignments

In `app/src/main/java/com/example/ui/portal/PortalShellScreen.kt`:
1. **Module Numbering Drift**: The original architecture blueprint specifies 24 distinct modules (`M01` through `M24`) housing exactly 450 functions. The previous code merged `M01` (Auth) and `M02` (Dashboard) into `"M01_DASHBOARD"`, split `M10` into `"M10_EXAMS"` and `"M11_RESULTS"`, shifted Study Materials to `"M12_MATERIALS"`, Certificates to `"M13_CERTIFICATES"`, Payroll to `"M14_PAYROLL"`, and completely omitted `M13_STAFF` (Staff & Faculty HR Management) from the main drawer.
2. **Teacher Portal Bottom Tabs**: The codebase set Tab 4 to "Grades" (`M11_RESULTS`). In practice, teachers prepare curriculum and distribute study materials daily; per blueprint specification, Tab 4 must be **Material & LMS (`M11`)**, while Attendance and Batches must be ordered sequentially (`Today` -> `Attendance` -> `Batches` -> `Material` -> `Inbox`).
3. **Student Portal Bottom Tabs**: The codebase forced Tab 2 as "Exams" (`M10_EXAMS`). A student checks their timetable and lab schedule multiple times a day, whereas exams happen periodically. Tab 2 must be **Timetable (`M06`)**, keeping Exams accessible via tactile home cards and the drawer.
4. **Parent Portal Navigation**: The Parent Portal requires a direct multi-child selector on the Top Bar or Home Hero (`M03_STUDENTS` / Ward 360°) to switch between siblings enrolled in different courses, rather than displaying generic institute-wide counts.


*Figure 1.2: System Navigation Architecture — 5-Tab Dedicated Bottom Bar and 7-Group Hierarchical Drawer.*

---

### 1.3 UI Copy, Typography & Localization Audit

1. **Hardcoded String Literals**: In `app/src/main/res/values/strings.xml`, only `<string name="app_name">Waqar Institute</string>` is declared. All button labels, headings, dialog messages, and validation errors are hardcoded inline in Kotlin files.
2. **Mixed Hindi-English Jargon**: Phrases like `"पासवर्ड (Password)"`, `"अपना अभिभावक यूजरनेम और पासवर्ड या Google लॉगिन चुनें"`, and `"Direct Sign Out Icon Pill (No Role Switcher)"` create visual dissonance. All UI strings must be routed through bilingual resource files (`values/strings.xml` and `values-hi/strings.xml`).
3. **Typography & Tabular Numerals (`tnum`)**: Critical financial figures (Fee Ledgers, Due Balances, Payroll Registers) currently render in standard proportional fonts. When numerical digits update via tickers, numbers jitter horizontally. All financial, date, and roll-number strings must explicitly enable `fontFeatureSettings = "tnum"` via `JetBrainsMono` or `PlusJakartaSans`.
4. **WCAG Contrast Deficiencies**: Subtitle text using `WciColors.TextMutedDark` (`#8D97C2`) fails the 4.5:1 contrast ratio when laid over semi-translucent glass cards that overlay bright auroral gradients. An 8% dark backing scrim (`Color(0x1405060F)`) must be applied behind text containers.

---

### 1.4 Animation & Motion Gaps (32-Motion Token Catalog)

While basic scale-press interactions were implemented, 22 of the 32 motion tokens defined in the UI/UX Blueprint are missing or improperly configured:
- **Missing Motion Tokens**: A02 (Card Entrance 3D rotation), A08 (Error shake keyframes), A09 (Success pulse rings), A10 (Gyroscope tilt parallax), A16 (Staggered list item entrance), A20 (Roster cell 3D flip 180ms), A22 (Shared-element container transform), A23 (Kanban lift & tilt drag), A26 (Hold-to-confirm progressive ring fill), and A28 (Streaming LLM chunk reveal).
- **GPU Overload Prevention**: Live background shaders (`NEON_AURORA_AGSL`) and multiple blur layers must be strictly disabled on `UiTier.REDUCED` and throttled during fast fling scrolling to preserve 60/120 FPS on budget Android hardware.

---

## 2. Global Design Language & Four-Material Architecture

The interface uses four distinct tactile materials. Each material serves a dedicated purpose so the UI remains disciplined, performant, and legible.

| Design Material | Target Portal | Core Visual Properties | Jetpack Compose Implementation | Performance Tiering |
| :--- | :--- | :--- | :--- | :--- |
| **Glassmorphism** | **Teacher Portal** | Frosted translucent surface, 16–28dp backdrop blur, 1dp luminous specular border, thin white highlight rim (22–30% alpha). | `Box` + `background(brush)` + `border(1.dp, borderBrush)` + `drawBehind` inner specular reflection. | Ultra/High: RenderEffect blur.<br>Standard/Reduced: Translucent solid scrim. |
| **Claymorphism** | **Student Portal** | Puffy 3D tactile pill, rounded corners (24–32dp), dual-bevel shadows (top-left white highlight, bottom-right soft dark shadow), floating elevated appearance. | `Box` + `shadow(14.dp)` + `drawRoundRect(white, Stroke)` + `drawRoundRect(dark, Stroke)` for 3D bevel. | Ultra/High: Double shadow pass.<br>Standard/Reduced: Single drop shadow. |
| **Liquid Glass** | **Parent Portal** | Soft fluid glass, calm warm amber/coral tones, gentle lens refraction, specular ribbon at upper third, reassuring and accessible. | `Box` + `background(gradient)` + `drawRect(specularVerticalGradient)` + soft ambient glow. | Ultra: AGSL lens shader.<br>High/Standard: Multi-stop gradient. |
| **Liquid Glass + Neon** | **Admin Portal** | High-density control glass, sharp luminous borders, vibrant violet/magenta neon sweeps, deep sapphire background, high-contrast numerical HUD. | `GlassCard` + `Modifier.neonBorder()` + `Canvas` particle aurora + animated gradient border sweep. | Ultra: AGSL runtime shader.<br>Standard: Pre-computed radial canvas blobs. |

### 2.1 Role Identity & Palette Tokens

Each portal operates in a dedicated chromatic environment derived from the brand tokens:

1. **Admin Portal (Control Center)**:
   - Primary Gradient: `#A970FF` (Electric Violet) -> `#FF4ECD` (Neon Magenta)
   - Glow Alpha: `0.35` | Background Base: Deep Midnight Sapphire (`#05060F`)
   - Aesthetic: Precision financial terminal, executive command center.
2. **Teacher Portal (Classroom Console)**:
   - Primary Gradient: `#2DFF9B` (Emerald Spring) -> `#D6FF3D` (Electric Lime)
   - Glow Alpha: `0.25` | Background Base: Deep Forest Slate (`#061118`)
   - Aesthetic: Crisp, high-contrast, high-speed classroom execution.
3. **Student Portal (Learning Hub)**:
   - Primary Gradient: `#00E1FF` (Cyber Cyan) -> `#4D7CFF` (Royal Azure)
   - Glow Alpha: `0.30` | Background Base: Deep Ocean Navy (`#060B24`)
   - Aesthetic: Gamified, rounded, tactile, motivating, 3D clay elements.
4. **Parent Portal (Family Window)**:
   - Primary Gradient: `#FFB02E` (Warm Amber) -> `#FF4D6D` (Sunset Coral)
   - Glow Alpha: `0.22` | Background Base: Deep Plum Slate (`#140714`)
   - Aesthetic: Warm, reassuring, legible, calm read-only transparency.

---

## 3. Login Portal: Neon Wave Authentication Architecture

The application implements a single unified native sign-in gateway supporting all four user roles with seamless biometric and credential authentication.


*Figure 3.1: Unified Neon Wave Login Gateway across Default, Verifying, Error Shake, and Success States.*

### 3.1 Login Interaction & State Machine

1. **Role Switcher Segmented Bar**:
   - Four role pills: `[ADMIN]`, `[STUDENT]`, `[TEACHER]`, `[PARENT]`.
   - **Motion Token A04 (Role Pill Spring)**: Selecting a role triggers an animated spring transition (`dampingRatio = 0.55f, stiffness = 300f`) where the selection pill glides to the active role while the entire screen's background waves and accent colors crossfade in 550ms.
   - **Motion Token A05 (Role Content Swap)**: Card heading, icon, input labels, and hints animate using `AnimatedContent` with a subtle vertical rise (`14dp`) and fade.
2. **Credentials & Field Mapping**:
   - **Admin**: Admin ID / Email + Master Password + Mandatory 2FA TOTP.
   - **Teacher**: Employee Code / Faculty Email + Password.
   - **Student**: Enrollment Number / Student Username + Password.
   - **Parent**: Registered Mobile Number / Parent ID + OTP or Google Sign-In.
3. **Verification & Error States**:
   - **A06 (CTA Shimmer)**: The primary button sweeps a 45° specular highlight every 3.2 seconds.
   - **A07 (Verify Spinner)**: On submit, the button compresses (`scale = 0.94f`) and displays a smooth spinning ring.
   - **A08 (Error Shake)**: On credential failure, the login card executes an 8-point horizontal keyframe shake (`-12dp, 12dp, -8dp, 8dp, -4dp, 4dp, 0dp`) paired with a haptic reject pulse.
   - **A09 (Success Pulse Rings)**: On successful authentication, three concentric neon rings expand outward from the CTA button, and the screen executes a shared-axis transition into the resolved portal shell.

---

## 4. Master Information Architecture & Placement Matrix

The application is structured into **24 functional modules (`M01` to `M24`)** housing **450 distinct functions**. Navigation is divided between a **5-Tab Bottom Bar** (daily frequent actions) and a **7-Category Side Drawer** (administrative, configuration, and deep-dive tools).

### 4.1 Admin Portal Architecture (Liquid Glass)

The Admin Portal is the complete operational control center for the institute owner.


*Figure 4.1: Admin Dashboard — Real-Time KPI HUD, Live Revenue Sparkline, and Quick Operation Tiles.*

#### Bottom Tab Bar (5 High-Frequency Tabs):
1. **Tab 1: Home (`M02_DASHBOARD`)** — Live telemetry, total students, fees collected, today's attendance ring, live revenue sparkline, quick actions.
2. **Tab 2: Students (`M03_STUDENTS`)** — Paged directory, prefix search, batch/course filters, student profile 360°, quick enrollment FAB.
3. **Tab 3: Attendance (`M07_ATTENDANCE`)** — Batch selector, one-tap roll call roster, high-speed QR barcode scanner, biometric gateway.
4. **Tab 4: Fees (`M08_FEES`)** — Daily collections, pending dues, installment plans, GST receipt generator, refund management.
5. **Tab 5: Inbox (`M16_ANNOUNCEMENTS`)** — Emergency broadcasts, push notifications, batch notices, SMS/WhatsApp delivery status.

#### Side Drawer Hierarchy (7 Business Groups & 24 Modules):
```
[DRAWER ROOT]
│
├── 1. CORE OPERATIONS
│   ├── M01: Executive Dashboard (Home)
│   └── M24: Institute Profile & Settings (Branding, Academic Year, GSTIN)
│
├── 2. PEOPLE & CRM
│   ├── M04: Admissions & Enquiry CRM (Kanban Pipeline, Leads, Follow-ups)
│   ├── M03: Student Directory 360° (Enrolled Students, Archived, Documents)
│   ├── M13: Staff & Faculty HR (Trainer Profiles, Workload, Permissions)
│   └── M15: Guardians & Parents (Parent Links, PTM Records, Consent)
│
├── 3. ACADEMIC HUB
│   ├── M05: Courses & Curriculum (Syllabus Builder, Course Fees)
│   ├── M06: Batches & Timetable (Lab Timetable, Trainer Assignment, Clash Detection)
│   ├── M10: Examinations & Results (CBT Engine, Question Bank, Mark Sheets)
│   ├── M11: Assignments & Study Material (LMS, Notes, Video Lectures)
│   ├── M12: Certificates & Verification (QR Diploma Generator, Public Verification)
│   ├── M17: Live Classes & Video (Agora WebRTC Rooms, Auto-Attendance)
│   └── M18: Gemini AI Hub (Copilot, Lesson Plan Generator, Quiz Maker)
│
├── 4. FINANCIAL LEDGER
│   ├── M08: Fees, Payments & Invoices (Fee Collection, Discount Approval)
│   ├── M09: Accounts & Expense Ledger (Petty Cash, Vendor Vouchers, P&L)
│   └── M14: Staff Payroll & Leave (Salary Structure, Payslips, Leave Approvals)
│
├── 5. INSIGHTS & TELEMETRY
│   └── M19: MIS Analytics & Reports (Drop-out Risk, Revenue Trajectory, Audit Export)
│
├── 6. FACILITY OPERATIONS
│   ├── M20: Computer Lab & Library Inventory (Lab PC Hardware, Software Licences, Books)
│   └── M21: Support Helpdesk & NPS (Student Tickets, Grievances, SLA Escalations)
│
└── 7. ENTERPRISE SYSTEM & VAULT
    ├── M22: Security Vault & Audit Logs (Tamper Detection, Session Revocation)
    └── M23: Cloud Backup & Data Tools (Disaster Recovery, CSV Export, Firestore Mirror)
```

---

### 4.2 Teacher Portal Architecture (Glassmorphism)

The Teacher Portal is optimized for high-speed classroom execution with zero administrative clutter.


*Figure 4.2: Teacher Portal — Today Schedule View, Interactive Roll-Call Roster, and Rapid Marks Entry.*

#### Bottom Tab Bar (5 Dedicated Classroom Tabs):
1. **Tab 1: Today (`M01_TODAY`)** — Chronological class schedule, next lecture countdown timer, lab allocation badge.
2. **Tab 2: Attendance (`M07_ATTENDANCE`)** — Active batch roll-call grid with 3D tactile flip cells (`Present`, `Absent`, `Late`).
3. **Tab 3: Batches (`M06_BATCHES`)** — Assigned student rosters, course syllabus completion progress bar.
4. **Tab 4: Material (`M11_MATERIALS`)** — Upload PDF lecture notes, code snippets, project assignments, lab tasks.
5. **Tab 5: Inbox (`M16_ANNOUNCEMENTS`)** — Institute circulars, batch announcement composer, teacher-parent direct chat.

#### Side Drawer Destinations:
- **Exams & CBT Marking (`M10`)** — Review subjective answers, enter practical marks, approve grace marks.
- **Live Class Host (`M17`)** — Start Agora video lecture, mute participants, trigger class recording.
- **Gemini AI Teaching Assistant (`M18`)** — Auto-generate 10-question MCQ quizzes from notes, generate lesson plans.
- **My Payroll & Leave (`M14`)** — View monthly payslips, apply for leave, view holiday calendar.
- **Helpdesk & Support (`M21`)** — Report lab computer hardware faults, request software installation.
- **Faculty Profile (`M24`)** — Bio, qualifications, active session devices, password management.

---

### 4.3 Student Portal Architecture (Claymorphism)

The Student Portal is styled in playful, encouraging 3D Claymorphism to stimulate learning and engagement.


*Figure 4.3: Student Portal — Tactile Clay Action Tiles, Interactive Timetable, and Gemini AI Tutor Chat.*

#### Bottom Tab Bar (5 Student Workspace Tabs):
1. **Tab 1: Home (`M01_HOME`)** — 3D Clay action tiles (`My Results`, `Fee Receipt`, `CBT Exam`, `AI Tutor`), next lecture tile.
2. **Tab 2: Timetable (`M06_TIMETABLE`)** — Daily lecture schedule, lab seat assignment, room number, teacher name.
3. **Tab 3: My Fees (`M08_FEES`)** — Fee ledger, upcoming installments, Razorpay online fee payment, GST tax receipt PDF download.
4. **Tab 4: Results (`M10_RESULTS`)** — Term exam mark sheets, grade cards, percentile rank, faculty remarks.
5. **Tab 5: Inbox (`M16_ANNOUNCEMENTS`)** — Institute notices, holiday alerts, assignment deadlines, teacher feedback.

#### Side Drawer Destinations:
- **My Attendance Log (`M07`)** — Verified biometric and QR attendance percentage ring, leave record.
- **Study Material & LMS (`M11`)** — Download lecture PDFs, practice files, source code archives, video tutorials.
- **Online CBT Exam Portal (`M10`)** — Timed practice tests, semester certification exams with anti-cheating screen lock.
- **Gemini AI Doubt Tutor (`M18`)** — 24x7 conversational coding and concept tutor with streaming code explanations.
- **QR Certificate & ID Card (`M12`)** — Digitally signed completion certificate with verifiable QR matrix.
- **Student Helpdesk (`M21`)** — Raise academic doubt tickets, lab PC technical support, fee queries.
- **My Profile (`M03`)** — Personal details, registered phone, emergency contact, password change.

---

### 4.4 Parent Portal Architecture (Liquid Glass)

The Parent Portal provides a serene, reassuring, read-only window into their child's academic and financial standing.

#### Bottom Tab Bar (5 Family Tabs):
1. **Tab 1: Ward (`M01_WARD`)** — Multi-child switcher, overall attendance health ring, latest exam performance summary.
2. **Tab 2: Attendance (`M07_ATTENDANCE`)** — Monthly calendar heatmap, real-time in/out entry timestamps, absence alerts.
3. **Tab 3: Fees (`M08_FEES`)** — Total fee, paid amount, pending balance, one-tap UPI fee payment, instant GST receipt.
4. **Tab 4: Marksheet (`M10_RESULTS`)** — Report cards, subject-wise marks breakdown, teacher remarks, rank in batch.
5. **Tab 5: Alerts (`M16_ANNOUNCEMENTS`)** — PTM invitations, fee payment due reminders, emergency weather holidays.

#### Side Drawer Destinations:
- **Child's Timetable (`M06`)** — Daily schedule, batch timings, lab sessions, assigned faculty details.
- **Direct Message Teacher (`M15`)** — Formal query thread with batch trainer regarding student progress.
- **Consent & Notification Preferences (`M15`)** — Opt-in for WhatsApp alerts, SMS notifications, excursion consents.
- **Verified Certificates (`M12`)** — View and download child's course completion diplomas.
- **Institute Support (`M21`)** — Direct contact line to Institute Administrator.
- **Guardian Profile (`M24`)** — Update address, alternative phone number, family details.

---

## 5. End-to-End User Workflows & Functional Logic

### 5.1 Workflow 1: Admission CRM Lead to Student Enrollment & QR ID Card
```
[Enquiry Capture] (F064)
       │
       ▼
[Kanban Pipeline Stage: New -> Follow-up -> Won] (F070, A23 Lift & Tilt)
       │
       ▼
[Admission Form Stepper] (F074)
  ├── 1. Personal & Guardian Details (Duplicate Phone Validation F044)
  ├── 2. Course & Batch Assignment (Capacity Check F107)
  ├── 3. Camera Capture: Student Photo (Base64 JPEG compression & face crop F046)
  └── 4. Admission Fee Downpayment (Cash / UPI / Cheque F076)
       │
       ▼
[Atomic Cloud Transaction]
  ├── Generates Sequential Enrollment No. "WCI-2026-XXXX" (F045)
  ├── Creates Firestore 'students/{uid}' record
  └── Emits FCM Welcome Notification to Student & Guardian (F306)
       │
       ▼
[Digital QR ID Card Generated] (F054, QrMatrixBadge)
  └── Displays Verifiable Dynamic QR Code with anti-screenshot FLAG_SECURE
```


*Figure 5.1: Admissions CRM Kanban Pipeline and Multi-Stage Registration Stepper.*

---

### 5.2 Workflow 2: Daily Classroom Attendance Roll-Call & Instant Parent Push
```
[Teacher selects Batch from 'Today' tab] (F103)
       │
       ▼
[Attendance Screen loads Roster Grid] (F126)
  ├── Option A: Manual Roster Tap (Flip animation A20: Green 'P', Red 'A', Amber 'L')
  └── Option B: High-Speed QR / Barcode Scan (A27 Scan line over student ID card)
       │
       ▼
[Teacher taps "SAVE ATTENDANCE" (F128, A06 Shimmer Button)]
       │
       ▼
[Server-Side Batch-Day Firestore Document Written] (C06 Scoped Rule)
       │
       ▼
[Autonomous Cloud Function Triggered] (F136)
  ├── If Student == ABSENT -> Triggers instant SMS & FCM Push to Guardian:
  │   "Alert: Your ward [Student Name] was marked Absent for DCA Lab at 09:00 AM."
  └── Updates Student 30-day rolling attendance percentage ring (A13 count-up)
```


*Figure 5.2: Attendance Roster Roll-Call and High-Speed Camera QR Barcode Scanner.*

---

### 5.3 Workflow 3: Fee Billing, Installment Settlement & GST PDF Receipt
```
[Admin opens Fees Tab -> Taps "Collect Fee"] (F149)
       │
       ▼
[Student Search by Name / Roll No. (F036, F037)]
       │
       ▼
[Ledger Card reveals Fee Breakdown] (F164, Liquid Glass)
  ├── Total Fee: Rs 12,000 | Paid: Rs 6,000 | Balance: Rs 6,000
  ├── Admin inputs Installment Amount: Rs 3,000
  └── Payment Mode: [Cash] [UPI / QR] [Bank Transfer] (F151)
       │
       ▼
[Atomic Payment Posting Callable Function] (recordPayment)
  ├── Appends Immutable Audit Transaction to 'fees/{txId}'
  ├── Decrements Student Balance in 'students/{uid}'
  └── Triggers DocumentExportHelper to compile GST Tax Invoice PDF (F153)
       │
       ▼
[Receipt PDF Card Pops Up with One-Tap Share (F154, A21 Bottom Sheet)]
  └── Direct Android Intent to WhatsApp / Print / Download
```


*Figure 5.3: Fee Collection Ledger, Payment Mode Selector, and GST Receipt Preview.*

---

### 5.4 Workflow 4: Online CBT Examination & Automated Result Publishing
```
[Student navigates to CBT Exams Tab] (F198)
       │
       ▼
[Pre-Exam Security Gate Activated] (PreExamSecurityGate.kt)
  ├── Enables Android WindowManager FLAG_SECURE (Blocks screen recording & screenshots)
  ├── Face Recognition Biometric Verification (ExamFaceAuthViewModel.kt)
  └── Locks App to Foreground (Detects backgrounding attempts & overlay attacks)
       │
       ▼
[Exam Engine Loads Question Stream from Firestore] (F194)
  ├── Countdown Timer HUD with Warning Flash (F199)
  ├── Autosaves answer choice on every tap (WorkManager background sync)
  └── On Timer Expiry / Submit -> Auto-locks submission
       │
       ▼
[Cloud Functions Auto-Evaluates Objective Questions] (F200)
  ├── Computes marks obtained, percentage, and grade scale (F204)
  ├── Generates Digital Mark Sheet with Principal Signature Watermark
  └── Realtime listener publishes results to Student & Parent Portals (F205)
```

---

### 5.5 Workflow 5: Gemini AI Doubt Tutor Interaction & Safety Quota Engine
```
[Student enters AI Tutor Chat Tab] (F336)
       │
       ▼
[Student types concept question, e.g., "Explain SQL Primary Key vs Foreign Key"]
       │
       ▼
[Secure Cloud Function Proxy Invoked: 'aiChat']
  ├── Validates Firebase Auth Token & verifies active Student Role claim (C01)
  ├── Enforces Per-User Monthly Quota & Rate Limits (F343)
  ├── Executes Content Safety & Moderation Filter
  └── Streams Gemini 1.5 Flash Response via Server-Sent Events (SSE)
       │
       ▼
[Chat Screen renders Streaming Text with Markdown Syntax Highlighting] (A28, JetBrainsMono)
  ├── Formats code blocks inside dark glass containers with one-tap "Copy Code"
  └── Adds "Explain Further" and "Give Practice Problem" interactive suggestion pills
```

---

## 6. Comprehensive 32-Animation Motion Token Catalog

All motion across the application adheres to strict physics tokens to prevent UI jank, preserve battery life, and guarantee accessibility compliance.

| Token ID | Animation Name | Target UI Components | Motion Physics & Timing | Compose Implementation | Reduced-Motion Fallback |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **A01** | Splash to Login | App Cold Launch | Icon scale + fade 300ms, then card rise. | `SplashScreen API` + `AnimatedVisibility` | Instant jump |
| **A02** | Login Card Entrance | Main Login Gateway | Rise 40dp, rotateX 18° -> 0°, scale 0.96 -> 1.0; 1000ms. | `Animatable` + `graphicsLayer` | 200ms alpha fade |
| **A03** | Conic Border Sweep | Neon Rim on Cards | 360° gradient sweep every 5.0s, linear loop. | `Modifier.neonBorder()` | Static 1dp border |
| **A04** | Role Pill Spring | Role Selector Bar | Spring damping 0.55, stiffness 300; color crossfade 700ms. | `animateFloatAsState` | Linear crossfade |
| **A05** | Role Content Swap | Login Portal Header | Fade + 14dp rise + scale 0.98 + blur 6dp -> 0dp; 550ms. | `AnimatedContent` | 150ms alpha fade |
| **A06** | CTA Shimmer Sweep | Primary Buttons | 45° light band sweeps across button every 3.2s. | `drawWithContent` + `infiniteRepeatable` | Disabled |
| **A07** | Verify Spinner | Submit Loading State | Continuous rotation 700ms per turn. | `CircularProgressIndicator` | Text "Verifying..." |
| **A08** | Error Shake | Failed Login / Validation | 8-point horizontal keyframe offset (`-12..12dp`) over 420ms. | `Animatable.animateTo()` keyframes | Red border flash |
| **A09** | Success Pulse Rings | Auth / Payment Success | 3 expanding concentric rings outward over 1.5s. | `Canvas` arc expander | Checkmark tick |
| **A10** | Gyroscope 3D Parallax | Dashboard Hero Cards | Card tilts up to 7° based on hardware gyroscope. | `SensorManager` + `graphicsLayer` | Static 2D card |
| **A11** | Bottom Tab Spring | Bottom Navigation Bar | Spring scale 1.0 -> 1.15; icon bounce + pill color morph. | `animateFloatAsState(bouncySpring)` | Instant highlight |
| **A12** | Drawer Slide & Scale | Modal Navigation Drawer | Main content scales to 0.94 and dims; drawer items stagger 30ms. | `ModalNavigationDrawer` | Standard slide |
| **A13** | KPI Counter Roll-Up | Dashboard Metric HUD | Digit increments from 0 to value over 600ms easeOut. | `animateIntAsState` + `tnum` font | Instant final integer |
| **A14** | Currency Money Ticker | Fee Balances & Revenue | Rolling vertical digit reel over 500ms with tabular figures. | `AnimatedContent` per digit | Instant formatted INR |
| **A15** | Chart Path Draw-In | Vico / Canvas Sparklines | SVG/Canvas path trim from 0% to 100% over 700ms. | `Animatable.animateTo(1f)` + `PathMeasure` | Static chart path |
| **A16** | Staggered List Entrance | Student & Fee Directories | Rows cascade in with 40ms stagger, 250ms slide-up each. | `Modifier.animateItem()` | Instant full list |
| **A17** | Skeleton Shimmer | Paged Lazy Lists | 1.2s linear shimmer over frosted glass placeholder blocks. | `Brush.linearGradient` shimmer | Static dark scrim |
| **A18** | Pull-to-Refresh Liquid | Directory Refresh | Liquid teardrop stretches down and snaps back with elastic spring. | `PullToRefreshBox` custom indicator | Standard circular arrow |
| **A19** | Swipe-to-Action | Session Revoke / Delete | Row translates horizontally to reveal red action button; 200ms snap. | `SwipeToDismissBox` | Overflow tap menu |
| **A20** | Roster Cell 3D Flip | Attendance Roll-Call | 180° rotationY flip over 180ms accompanied by haptic click. | `graphicsLayer(rotationY)` | Color toggle |
| **A21** | Glass Bottom Sheet | Filter & Action Pickers | Slide-up over 400ms springGentle; 40% frosted background scrim. | `ModalBottomSheet` | Instant pop-up |
| **A22** | Container Transform | Student Card to 360° | Expanding card shared-element bounds morph into full screen. | `SharedTransitionLayout` | Crossfade 300ms |
| **A23** | Kanban Drag & Settle | CRM Admissions Pipeline | Card lifts with 3° tilt and shadow elevation; settles with spring. | `detectDragGesturesAfterLongPress` | Tap to move |
| **A24** | Switch Thumb Spring | Settings Switches | Thumb snaps with bouncy spring; subtle haptic confirm. | `Switch` with spring animation | Instant toggle |
| **A25** | Success Confetti Blast | Fee Settlement / Certificate | Lottie celebration tick (900ms) paired with particle burst (1.2s). | `LottieCompose` | Green checkmark |
| **A26** | Hold-to-Confirm Ring | Destructive Actions | Circular ring fills progressively over 800ms of sustained touch. | `pointerInput` + `Canvas` stroke sweep | Confirmation dialog |
| **A27** | Viewfinder Scan Line | QR Code Reader | Horizontal neon laser bar oscillates vertically over camera view. | `infiniteRepeatable` linear sweep | Static target box |
| **A28** | LLM Streaming Reveal | Gemini AI Chat | Word tokens fade in progressively (~30ms per chunk). | `State` buffer append + soft alpha | Block text update |
| **A29** | Call Controls Auto-Hide | Live Video Classroom | Control bar smoothly fades out after 3.0s idle; tap restores it. | `AnimatedVisibility(fade)` | Always visible |
| **A30** | 3D Hero Mesh Float | Empty States / AI Header | Subtle 6-second vertical floating oscillation (+/- 6dp). | `infiniteRepeatable` sine curve | Static 2D vector |
| **A31** | Status Banner Slide | In-App Error / Alert | Banner slides down from top bar in 250ms; dismisses with swipe. | `slideInVertically` | Instant show/hide |
| **A32** | Checklist Tick Draw | Backup & Audit Drills | Vector checkmark strokes draw in dynamically over 200ms. | `Animatable` stroke length | Static check icon |

---

## 7. Complete 24-Module & 450-Function UI Mapping Matrix

Every function in the native architecture is indexed below with its exact UI host screen, navigation placement, visual material, and motion token.

| Module ID & Name | Total Functions | Target Portals | Primary Navigation Placement | UI Component Type | Material | Motion Token |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **M01: Authentication & Session** | 18 (F001–F018) | All Roles | Pre-login Gateway + Avatar Profile Menu | Full Scaffold + Dialog Sheets | Glass + Neon | A01–A09, A19 |
| **M02: Executive Dashboard** | 16 (F019–F034) | Admin, Teacher | **TAB 1: Home / Today** | Multi-Card Metric HUD + Vico Chart | Liquid Glass | A10, A13–A15 |
| **M03: Student Management 360°** | 30 (F035–F064) | Admin, Teacher (Read) | **TAB 2: Students** | Paged Directory + Profile Detail Screen | Liquid Glass | A16–A18, A22 |
| **M04: Admissions & Enquiry CRM** | 20 (F065–F084) | Admin Only | Drawer > People > Admissions | 3-Column Kanban Board + Multi-Step Form | Liquid Glass | A21, A23 |
| **M05: Course Catalog & Syllabus** | 18 (F085–F102) | Admin, Teacher | Drawer > Academics > Courses | Reorderable Course Cards + Syllabus Sheet | Liquid Glass | A16, A21 |
| **M06: Batches & Timetable** | 22 (F103–F124) | Admin, Teacher, Student | Drawer > Academics > Batches (**Student Tab 2**) | Calendar Grid + Slot Allocation Cards | Clay (Student) / Liquid (Admin) | A11, A21 |
| **M07: Attendance & Roll-Call** | 20 (F125–F144) | All Roles | **TAB 3: Attendance** (Admin/Teacher/Parent) | Interactive Roster Grid + QR Camera Sheet | Glass (Teacher) / Liquid (Admin) | A20, A27 |
| **M08: Fees, Payments & GST** | 28 (F145–F172) | Admin, Student, Parent | **TAB 4: Fees** (Admin/Student/Parent) | Tabular Money Ledger + Receipt Dialog | Liquid Glass (Admin/Parent) | A14, A21, A25 |
| **M09: Accounts & Expense Ledger** | 18 (F173–F190) | Admin Only | Drawer > Finance > Accounts | Cash Register Rows + Vendor Expense Sheet | Liquid Glass | A14, A21 |
| **M10: Examinations & Results** | 24 (F191–F214) | All Roles | Drawer > Academics > Exams (**Student Tab 4**) | Timed CBT Screen + Marks Entry Grid | Clay (Student) / Glass (Teacher) | A13, A25 |
| **M11: Assignments & LMS Material** | 18 (F215–F232) | Admin, Teacher, Student | Drawer > Academics > Material (**Teacher Tab 4**) | Document Grid + Media Picker Sheet | Glass (Teacher) / Clay (Student) | A16, A21 |
| **M12: Certificates & QR Diplomas** | 14 (F233–F246) | Admin, Student, Parent | Drawer > Academics > Certificates | Template Previewer + QR Matrix Card | Liquid Glass | A25, A32 |
| **M13: Teacher & Staff Management** | 22 (F247–F268) | Admin Only | Drawer > People > Staff | Staff Directory + Permission Matrix Editor | Liquid Glass | A16, A21 |
| **M14: Staff Payroll & Leave** | 16 (F269–F284) | Admin, Teacher | Drawer > Finance > Payroll | Salary Structure Rows + Payslip Viewer | Liquid Glass | A14, A21 |
| **M15: Guardian & Parent Linking** | 12 (F285–F296) | Admin, Parent | Drawer > People > Guardians | Parent Card + Direct Message Thread | Liquid Glass | A21, A28 |
| **M16: Notifications & Inbox** | 18 (F297–F314) | All Roles | **TAB 5: Inbox / Alerts** | Notice Feed + Broadcast Composer Sheet | All Portals (Material-Adaptive) | A16, A31 |
| **M17: Live Video Classroom** | 20 (F315–F334) | Admin, Teacher, Student | Drawer > Academics > Live Classes | Full-Screen Video Grid + WebRTC Controls | Glass (Minimal Chrome) | A29 |
| **M18: Gemini AI Tutor & Copilot** | 18 (F335–F352) | All Roles | Drawer > Academics > AI Hub | Conversational Chat UI + Code Highlighting | Glass + Neon Accents | A28, A30 |
| **M19: MIS Analytics & Reports** | 22 (F353–F374) | Admin Only | Drawer > Insights > Reports | Multi-Chart Analytics HUD + Export Dialog | Liquid Glass | A14, A15 |
| **M20: Inventory, Lab & Library** | 14 (F375–F388) | Admin Only | Drawer > Operations > Inventory | Hardware Asset Cards + Library Ledger | Liquid Glass | A16, A27 |
| **M21: Support Helpdesk & NPS** | 12 (F389–F400) | All Roles | Drawer > Operations > Support | Ticket Status Cards + Grievance Composer | All Portals (Material-Adaptive) | A21, A31 |
| **M22: Security Vault & Audit** | 20 (F401–F420) | Admin Only | Drawer > System > Security | Security Threat HUD + Active Session Rows | Liquid Glass + Red Neon | A19, A26 |
| **M23: Cloud Backup & Data Tools** | 14 (F421–F434) | Admin Only | Drawer > System > Backup | Backup Status Card + Checklist Stepper | Liquid Glass | A32 |
| **M24: Settings & Institute Profile**| 16 (F435–F450) | All Roles (Scoped) | Drawer > System > Settings | Form Sections + Haptic Toggle Switches | Liquid Glass | A24 |

---

## 8. Technical Implementation: Corrected Jetpack Compose Components

Below is the verified production Kotlin code resolving the material mapping flaws in `Materials.kt` and `PortalShellScreen.kt`.

### 8.1 Corrected `Materials.kt` Role-Adaptive Surface Engine
```kotlin
package com.example.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.data.model.PortalRole

/**
 * Portal-Adaptive Material Card Engine:
 * - TEACHER PORTAL -> Pure Glassmorphism (Frosted, specular rim, minimal chrome)
 * - STUDENT PORTAL -> 3D Tactile Claymorphism (Puffy dual-bevel inner & outer shadow)
 * - PARENT PORTAL  -> Reassuring Liquid Glass (Fluid specular ribbon, calm contrast)
 * - ADMIN PORTAL   -> High-Density Liquid Glass + Neon (Vibrant executive specular HUD)
 */
@Composable
fun PortalAdaptiveCard(
    role: PortalRole,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    glowColor: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = LocalRolePalette.current
    val accentStart = glowColor ?: palette.start
    val accentEnd = palette.end

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isPressed && onClick != null) 0.965f else 1f,
        animationSpec = WciMotion.bouncySpring(),
        label = "cardPressScale"
    )

    when (role) {
        PortalRole.TEACHER -> {
            // ==========================================
            // 1. TEACHER PORTAL: GLASSMORPHISM
            // ==========================================
            val shape = RoundedCornerShape(cornerRadius)
            val glassBrush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFF14241F).copy(alpha = 0.82f),
                    accentStart.copy(alpha = 0.16f),
                    Color(0xFF0A1512).copy(alpha = 0.88f)
                ),
                start = Offset(0f, 0f),
                end = Offset(800f, 700f)
            )
            val glassBorder = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.40f),
                    accentStart.copy(alpha = 0.60f),
                    Color.White.copy(alpha = 0.15f)
                )
            )

            Box(
                modifier = modifier
                    .scale(pressScale)
                    .shadow(8.dp, shape, ambientColor = accentStart.copy(alpha = 0.35f))
                    .clip(shape)
                    .background(glassBrush, shape)
                    .drawBehind {
                        // 1dp crisp specular rim across top edge
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.White.copy(alpha = 0.22f), Color.Transparent),
                                startY = 0f,
                                endY = size.height * 0.30f
                            )
                        )
                    }
                    .border(1.2.dp, glassBorder, shape)
                    .then(if (onClick != null) Modifier.clickable(interactionSource, null, onClick = onClick) else Modifier)
                    .padding(16.dp)
            ) {
                Column(content = content)
            }
        }

        PortalRole.STUDENT -> {
            // ==========================================
            // 2. STUDENT PORTAL: 3D CLAYMORPHISM
            // ==========================================
            val clayCorner = cornerRadius + 6.dp
            val shape = RoundedCornerShape(clayCorner)
            val clayBrush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFF1D285E).copy(alpha = 0.96f),
                    accentStart.copy(alpha = 0.28f),
                    Color(0xFF121A45).copy(alpha = 0.96f)
                )
            )

            Box(
                modifier = modifier
                    .scale(pressScale)
                    .shadow(16.dp, shape, ambientColor = accentStart.copy(alpha = 0.50f), spotColor = accentEnd.copy(alpha = 0.50f))
                    .clip(shape)
                    .background(clayBrush, shape)
                    .drawBehind {
                        val rPx = clayCorner.toPx()
                        // Top-left puffy white highlight bevel
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.26f),
                            topLeft = Offset(3.dp.toPx(), 3.dp.toPx()),
                            size = Size(size.width - 6.dp.toPx(), size.height - 6.dp.toPx()),
                            cornerRadius = CornerRadius(rPx, rPx),
                            style = Stroke(width = 3.dp.toPx())
                        )
                        // Bottom-right inner shadow bevel
                        drawRoundRect(
                            color = Color.Black.copy(alpha = 0.30f),
                            topLeft = Offset(5.dp.toPx(), 5.dp.toPx()),
                            size = Size(size.width - 6.dp.toPx(), size.height - 6.dp.toPx()),
                            cornerRadius = CornerRadius(rPx, rPx),
                            style = Stroke(width = 2.5.dp.toPx())
                        )
                    }
                    .border(2.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.65f), accentStart.copy(alpha = 0.70f))), shape)
                    .then(if (onClick != null) Modifier.clickable(interactionSource, null, onClick = onClick) else Modifier)
                    .padding(18.dp)
            ) {
                Column(content = content)
            }
        }

        PortalRole.PARENT, PortalRole.ADMIN -> {
            // ==========================================
            // 3. PARENT & ADMIN: LIQUID GLASS
            // ==========================================
            val shape = RoundedCornerShape(cornerRadius)
            val isParent = role == PortalRole.PARENT
            val liquidBrush = Brush.linearGradient(
                colors = if (isParent) {
                    listOf(
                        Color(0xFF2E1724).copy(alpha = 0.88f),
                        accentStart.copy(alpha = 0.22f),
                        Color(0xFF1F0D18).copy(alpha = 0.90f)
                    )
                } else {
                    listOf(
                        Color(0xFF182254).copy(alpha = 0.85f),
                        accentStart.copy(alpha = 0.26f),
                        accentEnd.copy(alpha = 0.18f),
                        Color(0xFF10173D).copy(alpha = 0.88f)
                    )
                }
            )

            Box(
                modifier = modifier
                    .scale(pressScale)
                    .shadow(12.dp, shape, ambientColor = accentStart.copy(alpha = 0.40f))
                    .clip(shape)
                    .background(liquidBrush, shape)
                    .drawBehind {
                        // Liquid specular reflection ribbon across top 38%
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.White.copy(alpha = 0.20f), Color.Transparent),
                                startY = 0f,
                                endY = size.height * 0.38f
                            )
                        )
                    }
                    .border(
                        width = if (isParent) 1.2.dp else 1.5.dp,
                        brush = Brush.linearGradient(
                            listOf(Color.White.copy(alpha = 0.50f), accentStart.copy(alpha = 0.70f), accentEnd.copy(alpha = 0.50f))
                        ),
                        shape = shape
                    )
                    .then(if (onClick != null) Modifier.clickable(interactionSource, null, onClick = onClick) else Modifier)
                    .padding(16.dp)
            ) {
                Column(content = content)
            }
        }
    }
}
```

---

### 8.2 Corrected Navigation Bottom Tabs in `PortalShellScreen.kt`
```kotlin
private fun bottomTabsForRole(role: PortalRole): List<ModuleDestination> = when (role) {
    PortalRole.ADMIN -> listOf(
        ModuleDestination("M02_DASHBOARD", "Dashboard", "Home", "Core", Icons.Default.Dashboard, Color(0xFFA855F7), Color(0xFFEC4899)),
        ModuleDestination("M03_STUDENTS", "Students", "Students", "People", Icons.Default.School, Color(0xFF00E5FF), Color(0xFF3B82F6)),
        ModuleDestination("M07_ATTENDANCE", "Attendance", "Attendance", "Academics", Icons.Default.QrCodeScanner, Color(0xFF10F596), Color(0xFF06B6D4)),
        ModuleDestination("M08_FEES", "Fees", "Fees", "Finance", Icons.Default.ReceiptLong, Color(0xFFFF5894), Color(0xFF8B5CF6)),
        ModuleDestination("M16_ANNOUNCEMENTS", "Inbox", "Inbox", "Comms", Icons.Default.Campaign, Color(0xFF38BDF8), Color(0xFFA855F7))
    )
    PortalRole.TEACHER -> listOf(
        ModuleDestination("M01_TODAY", "Today", "Today", "Core", Icons.Default.Today, Color(0xFF2DFF9B), Color(0xFFD6FF3D)),
        ModuleDestination("M07_ATTENDANCE", "Roll-Call", "Roll-Call", "Academics", Icons.Default.FactCheck, Color(0xFF10F596), Color(0xFF06B6D4)),
        ModuleDestination("M06_BATCHES", "Batches", "Batches", "Academics", Icons.Default.Schedule, Color(0xFF00E5FF), Color(0xFF3B82F6)),
        ModuleDestination("M11_MATERIALS", "LMS", "Material", "Academics", Icons.Default.LibraryBooks, Color(0xFF00E5FF), Color(0xFF10F596)),
        ModuleDestination("M16_ANNOUNCEMENTS", "Inbox", "Inbox", "Comms", Icons.Default.Campaign, Color(0xFF38BDF8), Color(0xFFA855F7))
    )
    PortalRole.STUDENT -> listOf(
        ModuleDestination("M01_HOME", "Home", "Home", "Core", Icons.Default.Home, Color(0xFF00E1FF), Color(0xFF4D7CFF)),
        ModuleDestination("M06_TIMETABLE", "Timetable", "Timetable", "Academics", Icons.Default.Schedule, Color(0xFF00E5FF), Color(0xFF3B82F6)),
        ModuleDestination("M08_FEES", "My Fees", "Fees", "Finance", Icons.Default.ReceiptLong, Color(0xFFFF5894), Color(0xFF8B5CF6)),
        ModuleDestination("M10_RESULTS", "Results", "Results", "Assessment", Icons.Default.EmojiEvents, Color(0xFFA855F7), Color(0xFF00E5FF)),
        ModuleDestination("M16_ANNOUNCEMENTS", "Inbox", "Inbox", "Comms", Icons.Default.Campaign, Color(0xFF38BDF8), Color(0xFFA855F7))
    )
    PortalRole.PARENT -> listOf(
        ModuleDestination("M01_WARD", "Ward 360°", "Ward", "Core", Icons.Default.ChildCare, Color(0xFFFFB02E), Color(0xFFFF4D6D)),
        ModuleDestination("M07_ATTENDANCE", "Attendance", "Attendance", "Academics", Icons.Default.EventAvailable, Color(0xFF10F596), Color(0xFF06B6D4)),
        ModuleDestination("M08_FEES", "Fees", "Fees", "Finance", Icons.Default.Payments, Color(0xFFFF5894), Color(0xFF8B5CF6)),
        ModuleDestination("M10_RESULTS", "Report Card", "Marks", "Assessment", Icons.Default.EmojiEvents, Color(0xFF818CF8), Color(0xFFE879F9)),
        ModuleDestination("M16_ANNOUNCEMENTS", "Alerts", "Alerts", "Comms", Icons.Default.NotificationsActive, Color(0xFF38BDF8), Color(0xFFA855F7))
    )
}
```

---

## 9. Conclusion & Actionable Next Steps

By strictly implementing the four-material separation (**Glassmorphism for Teacher**, **Claymorphism for Student**, **Liquid Glass for Parent and Admin**), restoring the true 24-module sequence across all navigation points, standardizing UI text into externalized resource bundles, and wiring the complete 32-motion token catalog, the Waqar Computer Institute application achieves a premier, bank-grade standard of performance, aesthetic excellence, and role-tailored usability.
