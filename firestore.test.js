const { test, before, after, beforeEach } = require('node:test');
const {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} = require('@firebase/rules-unit-testing');
const { readFileSync } = require('node:fs');

const PROJECT_ID = 'gen-lang-client-0309263247';
const DATABASE_ID = 'ai-studio-android-waqarins-b07db193-f2b2-469a-b782-7cb127ebd6a1';

let testEnv;

before(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules: readFileSync('firestore.rules', 'utf8'),
      host: '127.0.0.1',
      port: 8085,
    },
  });
});

beforeEach(async () => {
  await testEnv.clearFirestore();
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

test('1. Strict Zero-Trust: ALL unauthenticated reads & writes are DENIED except /certificates/{certId} exact get', async () => {
  const adminDb = testEnv.authenticatedContext('waqar_uid', {
    email: 'samdkhan8256972@gmail.com',
    email_verified: true,
    role: 'admin',
  }).firestore(DATABASE_ID);
  const unauthDb = testEnv.unauthenticatedContext().firestore(DATABASE_ID);
  const now = new Date(Date.now() - 2000);

  // Seed a public certificate for exact-ID QR verification test
  await assertSucceeds(
    adminDb.collection('certificates').doc('cert_qr_001').set({
      id: 'cert_qr_001',
      studentId: 'stu_001',
      studentName: 'Aarav Verma',
      courseName: 'ADCA',
      grade: 'A+',
      issueDateIso: '2026-11-01',
      verificationHash: 'sha256_verified_hash_001',
      revoked: false,
      ownerUid: 'waqar_uid',
      createdAt: now,
    })
  );

  // Unauthenticated reads on internal collections MUST ALL FAIL
  await assertFails(unauthDb.collection('users').doc('user_1').get());
  await assertFails(unauthDb.collection('portalCredentials').doc('admin').get());
  await assertFails(unauthDb.collection('portalCredentials').get());
  await assertFails(unauthDb.collection('students').doc('stu_001').get());
  await assertFails(unauthDb.collection('feeInvoices').doc('inv_001').get());
  await assertFails(unauthDb.collection('fees').doc('fee_001').get());
  await assertFails(unauthDb.collection('upi_transactions').doc('txn_001').get());
  await assertFails(unauthDb.collection('examAnswerKeys').doc('exm_001').get());
  await assertFails(unauthDb.collection('examResults').doc('res_001').get());
  await assertFails(unauthDb.collection('teachers').doc('t_001').get());
  await assertFails(unauthDb.collection('adminFaces').doc('waqar_uid').get());
  await assertFails(unauthDb.collection('sessions').doc('single_dev_rohit').get());
  await assertFails(unauthDb.collection('unknownCollection').doc('doc_1').get());

  // Unauthenticated writes MUST FAIL
  await assertFails(
    unauthDb.collection('students').doc('stu_spoof').set({
      id: 'stu_spoof',
      ownerUid: 'cred_admin',
    })
  );

  // Public QR verification by exact certificate ID SUCCEEDS, but listing all certificates FAILS
  await assertSucceeds(unauthDb.collection('certificates').doc('cert_qr_001').get());
  await assertFails(unauthDb.collection('certificates').get());
});

test('2. User profile & username reservation creation succeeds with valid schema and blocks spoofing/admin escalation', async () => {
  const aliceDb = testEnv.authenticatedContext('alice_uid', {
    email: 'alice@waqarinstitute.edu',
    email_verified: true,
    role: 'student',
  }).firestore(DATABASE_ID);

  const now = new Date(Date.now() - 2000);

  await assertSucceeds(
    aliceDb.collection('users').doc('alice_uid').set({
      uid: 'alice_uid',
      email: 'alice@waqarinstitute.edu',
      displayName: 'Alice Sharma',
      role: 'student',
      username: 'alice_sharma',
      phone: '+919876543210',
      mfaEnabled: false,
      totpSecret: '',
      linkedEntityId: 'stu_101',
      createdAt: now,
    })
  );

  await assertSucceeds(
    aliceDb.collection('usernames').doc('alice_sharma').set({
      username: 'alice_sharma',
      uid: 'alice_uid',
      role: 'student',
      email: 'alice@waqarinstitute.edu',
      createdAt: now,
    })
  );

  // Cross-user profile overwrite DENIED
  await assertFails(
    aliceDb.collection('users').doc('bob_uid').set({
      uid: 'bob_uid',
      email: 'bob@waqarinstitute.edu',
      displayName: 'Bob',
      role: 'student',
      username: 'bob',
      phone: '+919876543211',
      mfaEnabled: false,
      totpSecret: '',
      linkedEntityId: '',
      createdAt: now,
    })
  );

  // Student self-escalation to admin DENIED
  await assertFails(
    aliceDb.collection('users').doc('alice_uid').update({
      role: 'admin',
      isAdmin: true,
    })
  );
});

test('3. Student, PortalCredentials (Admin-only), AdminFaces, Fee, and AuditLog rules enforce Zero-Trust RBAC', async () => {
  const adminDb = testEnv.authenticatedContext('waqar_uid', {
    email: 'samdkhan8256972@gmail.com',
    email_verified: true,
    role: 'admin',
  }).firestore(DATABASE_ID);

  const studentDb = testEnv.authenticatedContext('stu_uid_1', {
    email: 'aarav@example.com',
    email_verified: true,
    role: 'student',
    linkedEntityId: 'stu_001',
  }).firestore(DATABASE_ID);

  const now = new Date(Date.now() - 2000);

  await assertSucceeds(
    adminDb.collection('students').doc('stu_001').set({
      id: 'stu_001',
      enrollmentNo: 'WCI-2026-001',
      fullName: 'Aarav Verma',
      email: 'aarav@example.com',
      phone: '+919811122233',
      guardianName: 'Rajesh Verma',
      guardianPhone: '+919811122234',
      courseId: 'crs_dca',
      courseName: 'Diploma in Computer Applications (DCA)',
      batchId: 'bat_morn_1',
      batchName: 'DCA Morning Batch A',
      status: 'ACTIVE',
      totalFeePaise: 1200000,
      paidFeePaise: 600000,
      attendancePercent: 92,
      photoUri: 'data:image/jpeg;base64,/9j/4AAQSkZJRg==',
      attachmentName: 'Aarav_Marksheet.pdf (140 KB)',
      ownerUid: 'waqar_uid',
      createdAt: now,
    })
  );

  // Student can read own student doc via linkedEntityId claim, but CANNOT read other students
  await assertSucceeds(studentDb.collection('students').doc('stu_001').get());
  await assertFails(studentDb.collection('students').doc('stu_other').get());

  // Admin creates a student portal credential in Cloud Firestore
  await assertSucceeds(
    adminDb.collection('portalCredentials').doc('aarav_wci').set({
      username: 'aarav_wci',
      passwordHash: '3a7bd3e2360a3d29eea436fcfb7e44c735d117c42d1c1835420b6b9942dd4f1b',
      passwordSalt: '00112233445566778899aabbccddeeff',
      role: 'student',
      linkedEntityId: 'stu_001',
      displayName: 'Aarav Verma',
      assignedByUid: 'waqar_uid',
      active: true,
      createdAt: now,
    })
  );

  // Zero-Trust: Student CANNOT read or list portalCredentials (login handled server-side by issuePortalCustomToken)
  await assertFails(studentDb.collection('portalCredentials').doc('aarav_wci').get());
  await assertFails(studentDb.collection('portalCredentials').get());

  // Admin CAN read & list portalCredentials and write /adminFaces
  await assertSucceeds(adminDb.collection('portalCredentials').get());
  await assertSucceeds(
    adminDb.collection('adminFaces').doc('waqar_uid').set({
      adminUid: 'waqar_uid',
      embedding: [0.1, 0.2, 0.3],
      updatedAt: now,
    })
  );
  await assertFails(studentDb.collection('adminFaces').doc('waqar_uid').get());

  await assertFails(
    adminDb.collection('students').doc('stu_bad').set({
      id: 'stu_bad',
      enrollmentNo: 'WCI-2026-002',
      fullName: 'Bad Record',
      email: 'bad@example.com',
      phone: '+919811122233',
      guardianName: 'G',
      guardianPhone: '123',
      courseId: 'crs_dca',
      courseName: 'DCA',
      batchId: 'bat_1',
      batchName: 'Batch 1',
      status: 'INVALID_STATUS',
      totalFeePaise: 1000,
      paidFeePaise: 0,
      attendancePercent: 100,
      photoUri: '',
      attachmentName: '',
      ownerUid: 'waqar_uid',
      createdAt: now,
    })
  );

  await assertSucceeds(
    adminDb.collection('fees').doc('fee_001').set({
      id: 'fee_001',
      receiptNo: 'RCPT-2026-0001',
      studentId: 'stu_001',
      studentName: 'Aarav Verma',
      courseName: 'Diploma in Computer Applications (DCA)',
      title: 'Installment 1 - Admission & Tuition',
      totalAmountPaise: 1200000,
      paidAmountPaise: 600000,
      discountPaise: 0,
      finePaise: 0,
      paymentMode: 'UPI',
      status: 'PARTIAL',
      dueDateIso: '2026-12-01',
      paidAtIso: '2026-11-10',
      ownerUid: 'waqar_uid',
      createdAt: now,
    })
  );

  await assertSucceeds(
    adminDb.collection('auditLogs').doc('aud_001').set({
      id: 'aud_001',
      actorUid: 'waqar_uid',
      actorEmail: 'samdkhan8256972@gmail.com',
      action: 'CREATE_STUDENT',
      module: 'M03_STUDENTS',
      targetId: 'stu_001',
      severity: 'INFO',
      details: 'Enrolled Aarav Verma in DCA Morning Batch A',
      createdAt: now,
    })
  );

  await assertFails(
    adminDb.collection('auditLogs').doc('aud_001').update({
      details: 'Tampered audit log',
    })
  );
});

test('4. Custom-Token authenticated portal user syncs own profile & single-device session while unauth is denied', async () => {
  const adminDb = testEnv.authenticatedContext('waqar_uid', {
    email: 'samdkhan8256972@gmail.com',
    email_verified: true,
    role: 'admin',
  }).firestore(DATABASE_ID);
  const rohitCustomTokenDb = testEnv.authenticatedContext('cred_rohit_wci', {
    role: 'student',
    portalUid: 'cred_rohit_wci',
    username: 'rohit_wci',
    linkedEntityId: 'stu_rohit_1',
  }).firestore(DATABASE_ID);
  const unauthDb = testEnv.unauthenticatedContext().firestore(DATABASE_ID);
  const now = new Date(Date.now() - 2000);

  await assertSucceeds(
    adminDb.collection('portalCredentials').doc('rohit_wci').set({
      username: 'rohit_wci',
      passwordHash: '3a7bd3e2360a3d29eea436fcfb7e44c735d117c42d1c1835420b6b9942dd4f1b',
      passwordSalt: '00112233445566778899aabbccddeeff',
      role: 'student',
      linkedEntityId: 'stu_rohit_1',
      displayName: 'Rohit Verma',
      assignedByUid: 'waqar_uid',
      active: true,
      isStudent: true,
      isTeacher: false,
      isParent: false,
      isAdmin: false,
      portalRoles: ['student'],
      createdAt: now,
    })
  );

  // Authenticated Custom-Token student succeeds on own profile & session
  await assertSucceeds(
    rohitCustomTokenDb.collection('users').doc('cred_rohit_wci').set({
      uid: 'cred_rohit_wci',
      email: 'rohit_wci@waqarinstitute.edu',
      displayName: 'Rohit Verma',
      role: 'student',
      username: 'rohit_wci',
      phone: '',
      mfaEnabled: false,
      totpSecret: '',
      linkedEntityId: 'stu_rohit_1',
      isStudent: true,
      isTeacher: false,
      isParent: false,
      isAdmin: false,
      portalRoles: ['student'],
      activeSessionToken: 'tok_abc_123',
      activeDeviceId: 'DEV_01',
      lastActiveTimestamp: now,
      createdAt: now,
    })
  );

  await assertSucceeds(
    rohitCustomTokenDb.collection('sessions').doc('single_dev_rohit_wci').set({
      sessionId: 'single_dev_rohit_wci',
      uid: 'cred_rohit_wci',
      deviceName: 'DEV:DEV_01 | Pixel',
      platform: 'Android 16',
      active: true,
      activeSessionToken: 'tok_abc_123',
      activeDeviceId: 'DEV_01',
      lastActiveTimestamp: now,
      createdAt: now,
    })
  );

  await assertSucceeds(
    rohitCustomTokenDb.collection('sessions').doc('single_dev_rohit_wci').get()
  );

  // Unauthenticated access to the same session is strictly DENIED
  await assertFails(
    unauthDb.collection('sessions').doc('single_dev_rohit_wci').get()
  );
});

test('5. Zero-trust hardening blocks student examResult self-writes, cross-student profile reads, and immutableLedger tampering', async () => {
  const adminDb = testEnv.authenticatedContext('waqar_uid', {
    email: 'samdkhan8256972@gmail.com',
    email_verified: true,
    role: 'admin',
  }).firestore(DATABASE_ID);
  const studentDb = testEnv.authenticatedContext('stu_uid_1', {
    email: 'aarav@example.com',
    email_verified: true,
    role: 'student',
    linkedEntityId: 'stu_001',
  }).firestore(DATABASE_ID);
  const unauthDb = testEnv.unauthenticatedContext().firestore(DATABASE_ID);
  const now = new Date(Date.now() - 2000);

  await assertSucceeds(
    adminDb.collection('users').doc('other_user_uid').set({
      uid: 'other_user_uid',
      email: 'other@waqarinstitute.edu',
      displayName: 'Other User',
      role: 'student',
      username: 'other_user',
      phone: '+919999988888',
      mfaEnabled: true,
      totpSecret: 'SECRETBASE32KEY',
      linkedEntityId: 'stu_other',
      createdAt: now,
    })
  );

  // 1. Authenticated student CANNOT read another user's profile
  await assertFails(studentDb.collection('users').doc('other_user_uid').get());

  // 2. Student CANNOT directly write or update /examResults (authoritative writes come from Cloud Function Admin SDK or Staff)
  await assertFails(
    studentDb.collection('examResults').doc('sub_001').set({
      id: 'sub_001',
      examId: 'ex_001',
      examTitle: 'DCA Final Exam',
      studentId: 'stu_001',
      studentName: 'Aarav Verma',
      marksObtained: 100,
      totalMarks: 100,
      grade: 'A+',
      rank: 1,
      ownerUid: 'stu_uid_1',
      createdAt: now,
    })
  );

  // Admin CAN write & update examResult, and student CAN read their own examResult
  await assertSucceeds(
    adminDb.collection('examResults').doc('sub_001').set({
      id: 'sub_001',
      examId: 'ex_001',
      examTitle: 'DCA Final Exam',
      studentId: 'stu_001',
      studentName: 'Aarav Verma',
      marksObtained: 72,
      totalMarks: 100,
      grade: 'A',
      rank: 1,
      ownerUid: 'stu_uid_1',
      createdAt: now,
    })
  );
  await assertSucceeds(studentDb.collection('examResults').doc('sub_001').get());
  await assertFails(
    studentDb.collection('examResults').doc('sub_001').update({
      marksObtained: 100,
      grade: 'A+',
    })
  );

  // 3. Unauthenticated caller CANNOT delete student records
  await assertFails(unauthDb.collection('students').doc('stu_001').delete());

  // 4. Immutable ledger allows valid append creation, but denies update and delete even for Admin
  await assertSucceeds(
    adminDb.collection('immutableLedger').doc('evt_001').set({
      sequenceIndex: 1,
      eventType: 'FEE_RECEIPT_ISSUED',
      actorId: 'waqar_uid',
      prevEventHash: 'GENESIS_00000000',
      currentEventHash: 'a9f4e2c18b7d60123456789abcdef012',
      createdAt: now,
    })
  );

  await assertFails(
    adminDb.collection('immutableLedger').doc('evt_001').update({
      currentEventHash: 'tampered_hash_value',
    })
  );
  await assertFails(adminDb.collection('immutableLedger').doc('evt_001').delete());
});

test('6. Teachers biometric vault denies unauthenticated & student access while allowing JWT Admin enrollment', async () => {
  const adminDb = testEnv.authenticatedContext('waqar_uid', {
    email: 'samdkhan8256972@gmail.com',
    email_verified: true,
    role: 'admin',
  }).firestore(DATABASE_ID);
  const customTokenAdminDb = testEnv.authenticatedContext('cred_admin', {
    role: 'admin',
    portalUid: 'cred_admin',
    username: 'waqar',
  }).firestore(DATABASE_ID);
  const studentDb = testEnv.authenticatedContext('stu_uid_1', {
    email: 'aarav@example.com',
    email_verified: true,
    role: 'student',
  }).firestore(DATABASE_ID);
  const unauthDb = testEnv.unauthenticatedContext().firestore(DATABASE_ID);
  const embedding = Array.from({ length: 128 }, (_, i) => (i === 0 ? 1.0 : 0.0));

  // Unauthenticated caller is ALWAYS denied (even with spoofed enrolledByAdminUid)
  await assertFails(
    unauthDb.collection('teachers').doc('stf_101').set({
      id: 'stf_101',
      name: 'Teacher One',
      enrolledByAdminUid: 'admin_root',
      faceEmbedding: embedding,
    })
  );

  // Authenticated student CANNOT read or write teacher biometric embeddings
  await assertFails(
    studentDb.collection('teachers').doc('stf_101').set({
      id: 'stf_101',
      name: 'Teacher One',
      enrolledByAdminUid: 'admin_root',
      faceEmbedding: embedding,
    })
  );

  // Custom-Token Admin CAN enroll teacher biometric embedding
  await assertSucceeds(
    customTokenAdminDb.collection('teachers').doc('stf_101').set({
      id: 'stf_101',
      name: 'Teacher One',
      enrolledByAdminUid: 'cred_admin',
      faceEmbedding: embedding,
    })
  );

  // Bootstrapped Google Admin CAN enroll teacher biometric embedding
  await assertSucceeds(
    adminDb.collection('teachers').doc('stf_102').set({
      id: 'stf_102',
      name: 'Teacher Two',
      enrolledByAdminUid: 'waqar_uid',
      faceEmbedding: embedding,
    })
  );
});

test('7. Zero-Fee UPI Intent & Auto-EMI rules block unauth access, student self-approval, and duplicate UTR replay while allowing Admin 1-Tap confirmation', async () => {
  const adminDb = testEnv.authenticatedContext('waqar_uid', {
    email: 'samdkhan8256972@gmail.com',
    email_verified: true,
    role: 'admin',
  }).firestore(DATABASE_ID);
  const studentDb = testEnv.authenticatedContext('stu_uid_1', {
    email: 'aarav@example.com',
    email_verified: true,
    role: 'student',
    linkedEntityId: 'stu_1001',
  }).firestore(DATABASE_ID);
  const unauthDb = testEnv.unauthenticatedContext().firestore(DATABASE_ID);

  const nowTs = new Date(Date.now() - 2000);
  const baseTxn = {
    id: 'upi_txn_1001',
    student_id: 'stu_1001',
    student_uid: 'stu_uid_1',
    student_name: 'Aarav Sharma',
    course_name: 'ADCA',
    amount: 1500,
    amount_paise: 150000,
    payment_type: 'MONTHLY_FEE',
    tr_ref: 'WCI1731888000ABCDEF',
    tn_note: 'WCI|stu_1001|MONTHLY_FEE|N:12345678',
    signal_nonce: 'nonce_9988776655',
    intent_hash: 'a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90',
    utr_number: '432198765432',
    upi_package: 'com.google.android.apps.nbu.paisa.user',
    device_fingerprint: 'HW_FP_AARAV_01',
    apk_signature_sha256: '0123456789ABCDEF0123456789ABCDEF',
    timestamp: 1731888000000,
    expires_at_ms: 1731888120000,
    status: 'PENDING_APPROVAL',
    verified_by_uid: '',
    createdAt: nowTs,
  };

  // Unauthenticated client CANNOT submit UPI transactions
  await assertFails(
    unauthDb.collection('upi_transactions').doc('upi_txn_unauth').set({
      ...baseTxn,
      id: 'upi_txn_unauth',
    })
  );

  // Student CANNOT self-create a transaction with status VERIFIED_PAID
  await assertFails(
    studentDb.collection('upi_transactions').doc('upi_txn_spoof').set({
      ...baseTxn,
      id: 'upi_txn_spoof',
      status: 'VERIFIED_PAID',
    })
  );

  // Student CAN submit a valid PENDING_APPROVAL UPI intent result for their own student_uid
  await assertSucceeds(
    studentDb.collection('upi_transactions').doc('upi_txn_1001').set(baseTxn)
  );

  // Student CANNOT self-transition to VERIFIED_PAID
  await assertFails(
    studentDb.collection('upi_transactions').doc('upi_txn_1001').update({
      status: 'VERIFIED_PAID',
    })
  );

  // Admin CAN 1-Tap Confirm and transition status to VERIFIED_PAID
  await assertSucceeds(
    adminDb.collection('upi_transactions').doc('upi_txn_1001').update({
      status: 'VERIFIED_PAID',
      verified_by_uid: 'waqar_uid',
    })
  );

  // Single-use UTR lock succeeds on first use and FAILS on duplicate UTR replay
  await assertSucceeds(
    studentDb.collection('used_utr_locks').doc('432198765432').set({
      utr_number: '432198765432',
      txn_id: 'upi_txn_1001',
      student_id: 'stu_1001',
      amount_paise: 150000,
      locked_at_ms: 1731888010000,
    })
  );
  await assertFails(
    studentDb.collection('used_utr_locks').doc('432198765432').set({
      utr_number: '432198765432',
      txn_id: 'upi_txn_9999',
      student_id: 'stu_9999',
      amount_paise: 150000,
      locked_at_ms: 1731888090000,
    })
  );

  // Admin can configure 4-month overdue Auto-EMI in student_fees_ledger
  await assertSucceeds(
    adminDb.collection('student_fees_ledger').doc('stu_1001').set({
      id: 'stu_1001',
      student_id: 'stu_1001',
      student_name: 'Aarav Sharma',
      enrollment_no: 'WCI-2026-1001',
      course_name: 'ADCA',
      batch_name: 'Morning Batch A',
      total_fee_paise: 1800000,
      paid_fee_paise: 600000,
      monthly_fee_paise: 150000,
      emi_installment_paise: 200000,
      monthly_payment_status: 'EMI_ACTIVE',
      overdue_months: 4,
      auto_emi_active: true,
      emi_billing_date: 5,
      next_due_date_iso: '2026-12-05',
      last_emi_notice_iso: 'Auto-EMI Activated for 5th of every month',
      last_utr_number: '432198765432',
      owner_uid: 'waqar_uid',
      updatedAt: 1731888050000,
      createdAt: nowTs,
    })
  );

  // Immutable institute_audit_logs can be created by authenticated user for own actor_uid, never deleted
  await assertSucceeds(
    studentDb.collection('institute_audit_logs').doc('upi_aud_1').set({
      id: 'upi_aud_1',
      actor_uid: 'stu_uid_1',
      actor_name: 'Aarav Sharma',
      student_id: 'stu_1001',
      event_type: 'UPI_CHALLENGE_SUBMITTED',
      severity: 'INFO',
      utr_number: '432198765432',
      intent_hash: baseTxn.intent_hash,
      device_fingerprint: 'HW_FP_AARAV_01',
      details: 'Submitted UPI UTR 432198765432 for Admin 1-Tap approval',
      timestamp: 1731888010000,
      createdAt: nowTs,
    })
  );
  await assertFails(
    adminDb.collection('institute_audit_logs').doc('upi_aud_1').delete()
  );
});

test('8. Enterprise Upgrades: /examAnswerKeys strictly Staff/Admin only, Smart 24h Exams & Exact % Results succeed', async () => {
  const adminDb = testEnv.authenticatedContext('waqar_uid', {
    email: 'samdkhan8256972@gmail.com',
    email_verified: true,
    role: 'admin',
  }).firestore(DATABASE_ID);
  const teacherDb = testEnv.authenticatedContext('teacher_uid_1', {
    role: 'teacher',
  }).firestore(DATABASE_ID);
  const studentDb = testEnv.authenticatedContext('stu_uid_1', {
    email: 'student.candidate@example.com',
    email_verified: true,
    role: 'student',
  }).firestore(DATABASE_ID);
  const unauthDb = testEnv.unauthenticatedContext().firestore(DATABASE_ID);
  const nowTs = new Date(Date.now() - 2000);

  await assertSucceeds(
    adminDb.collection('examAnswerKeys').doc('exm_smart_01').set({
      id: 'exm_smart_01',
      examId: 'exm_smart_01',
      examTitle: 'CCC 30-Second Rapid Quiz',
      batchId: 'bat_morning_01',
      answersByMasterQId: { Q001: 1, Q002: 3 },
      questionCount: 2,
      ownerUid: 'waqar_uid',
      updatedAt: nowTs,
    })
  );

  await assertSucceeds(adminDb.collection('examAnswerKeys').doc('exm_smart_01').get());
  await assertSucceeds(teacherDb.collection('examAnswerKeys').doc('exm_smart_01').get());
  await assertFails(studentDb.collection('examAnswerKeys').doc('exm_smart_01').get());
  await assertFails(unauthDb.collection('examAnswerKeys').doc('exm_smart_01').get());
  await assertFails(
    studentDb.collection('examAnswerKeys').doc('exm_spoof_key').set({
      id: 'exm_spoof_key',
      examId: 'exm_spoof_key',
      answersByMasterQId: { Q001: 0 },
      ownerUid: 'stu_uid_1',
    })
  );

  await assertSucceeds(
    adminDb.collection('batches').doc('bat_morning_01').set({
      id: 'bat_morning_01',
      code: 'ADCA-M1',
      name: 'Morning Batch 8AM',
      courseName: 'ADCA',
      teacherId: 'stf_t01',
      teacherName: 'Faculty One',
      batchAdminTeacherId: 'stf_t01',
      batchAdminTeacherName: 'Faculty One',
      canBatchAdminTakeAttendance: true,
      canBatchAdminScheduleExams: true,
      roomName: 'Lab-1',
      daysOfWeek: 'Mon-Sat',
      startTime: '08:00',
      endTime: '10:00',
      capacity: 30,
      enrolledCount: 24,
      status: 'RUNNING',
      ownerUid: 'waqar_uid',
      createdAt: nowTs,
    })
  );

  await assertSucceeds(
    adminDb.collection('exams').doc('exm_smart_01').set({
      id: 'exm_smart_01',
      title: 'CCC 30-Second Rapid Quiz',
      courseId: 'crs_ccc',
      courseName: 'CCC',
      batchId: 'bat_morning_01',
      batchName: 'Morning Batch 8AM',
      examType: 'MCQ_ONLINE',
      durationMinutes: 1,
      durationSeconds: 30,
      startTime24h: '14:30',
      scheduledStartEpochMs: 1796029200000,
      autoAssignLabPcSeats: true,
      createdByRole: 'admin',
      totalMarks: 100,
      passingMarks: 40,
      scheduledDateIso: '2026-12-01',
      questionsJson: '[]',
      status: 'PUBLISHED',
      ownerUid: 'waqar_uid',
      createdAt: nowTs,
    })
  );

  await assertSucceeds(
    adminDb.collection('examResults').doc('res_exact_01').set({
      id: 'res_exact_01',
      examId: 'exm_smart_01',
      examTitle: 'CCC 30-Second Rapid Quiz',
      studentId: 'stu_cand_01',
      studentName: 'Candidate One',
      marksObtained: 128,
      totalMarks: 150,
      exactPercentage: 85.33,
      formattedPercentage: '85.33%',
      correctCount: 128,
      totalQuestions: 150,
      grade: 'A',
      rank: 1,
      status: 'PASSED',
      remarks: 'Graded authoritatively',
      hashChainHead: 'abc123def456',
      ownerUid: 'waqar_uid',
      createdAt: nowTs,
    })
  );
});

test('9. Zero-Bill AI Tutor collections enforce strict Zero-Trust RBAC (ai_shared_cache, ai_student_quotas, ai_tutor_locks)', async () => {
  const adminDb = testEnv.authenticatedContext('waqar_uid', {
    email: 'samdkhan8256972@gmail.com',
    email_verified: true,
    role: 'admin',
  }).firestore(DATABASE_ID);
  const student1Db = testEnv.authenticatedContext('stu_uid_1', {
    role: 'student',
    linkedEntityId: 'stu_001',
  }).firestore(DATABASE_ID);
  const student2Db = testEnv.authenticatedContext('stu_uid_2', {
    role: 'student',
    linkedEntityId: 'stu_002',
  }).firestore(DATABASE_ID);
  const unauthDb = testEnv.unauthenticatedContext().firestore(DATABASE_ID);

  // Admin seeds docs (mirroring Cloud Function Admin SDK writes)
  await assertSucceeds(
    adminDb.collection('ai_shared_cache').doc('hash_python_dict').set({
      promptHash: 'hash_python_dict',
      featureType: 'TUTOR_CHAT',
      responseText: 'Python dictionaries map keys to values in O(1) time.',
      modelName: 'groq/openai/gpt-oss-120b',
    })
  );
  await assertSucceeds(
    adminDb.collection('ai_student_quotas').doc('stu_uid_1').set({
      uid: 'stu_uid_1',
      dateUtc: '2026-11-19',
      liveCallsUsed: 2,
      dailyCap: 5,
    })
  );
  await assertSucceeds(
    adminDb.collection('ai_tutor_locks').doc('stu_uid_1').set({
      uid: 'stu_uid_1',
      strikeCount: 2,
      locked: true,
      lockedUntilMs: Date.now() + 86400000,
    })
  );

  // Unauthenticated caller is strictly denied on all 3 collections
  await assertFails(unauthDb.collection('ai_shared_cache').doc('hash_python_dict').get());
  await assertFails(unauthDb.collection('ai_student_quotas').doc('stu_uid_1').get());
  await assertFails(unauthDb.collection('ai_tutor_locks').doc('stu_uid_1').get());

  // Authenticated student CAN read shared cache and own quota/lock, but CANNOT write any of the 3
  await assertSucceeds(student1Db.collection('ai_shared_cache').doc('hash_python_dict').get());
  await assertSucceeds(student1Db.collection('ai_student_quotas').doc('stu_uid_1').get());
  await assertSucceeds(student1Db.collection('ai_tutor_locks').doc('stu_uid_1').get());

  await assertFails(
    student1Db.collection('ai_shared_cache').doc('hash_python_dict').set({ responseText: 'tampered' })
  );
  await assertFails(
    student1Db.collection('ai_student_quotas').doc('stu_uid_1').update({ liveCallsUsed: 0 })
  );
  await assertFails(
    student1Db.collection('ai_tutor_locks').doc('stu_uid_1').update({ locked: false, strikeCount: 0 })
  );

  // Student 2 CANNOT read Student 1's quota or security lock
  await assertFails(student2Db.collection('ai_student_quotas').doc('stu_uid_1').get());
  await assertFails(student2Db.collection('ai_tutor_locks').doc('stu_uid_1').get());

  // Admin CAN unlock Student 1's 24h AI Tutor lock
  await assertSucceeds(
    adminDb.collection('ai_tutor_locks').doc('stu_uid_1').update({ locked: false, strikeCount: 0 })
  );
});

test('10. Elite Security Layers: device_pubkeys, dpop_nonces, examSessions, revoked_devices, and server_audit_hash_chain enforce strict Zero-Trust', async () => {
  const adminDb = testEnv.authenticatedContext('admin_waqar_root', {
    role: 'admin',
    portalUid: 'admin_waqar_root',
    username: 'waqar',
  }).firestore(DATABASE_ID);
  const student1Db = testEnv.authenticatedContext('stu_uid_1', {
    role: 'student',
    linkedEntityId: 'stu_001',
  }).firestore(DATABASE_ID);
  const student2Db = testEnv.authenticatedContext('stu_uid_2', {
    role: 'student',
    linkedEntityId: 'stu_002',
  }).firestore(DATABASE_ID);
  const unauthDb = testEnv.unauthenticatedContext().firestore(DATABASE_ID);

  // 1. device_pubkeys: owner can register own key; cannot spoof another user's key; unauth denied
  await assertSucceeds(
    student1Db.collection('device_pubkeys').doc('stu_uid_1_HWFP01').set({
      uid: 'stu_uid_1',
      deviceFp: 'HWFP01',
      spkiBase64: 'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1234567890abcdef1234567890abcdef',
    })
  );
  await assertSucceeds(student1Db.collection('device_pubkeys').doc('stu_uid_1_HWFP01').get());
  await assertFails(student2Db.collection('device_pubkeys').doc('stu_uid_1_HWFP01').get());
  await assertFails(
    student2Db.collection('device_pubkeys').doc('stu_uid_1_HWFP02').set({
      uid: 'stu_uid_1',
      deviceFp: 'HWFP02',
      spkiBase64: 'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1234567890abcdef1234567890abcdef',
    })
  );
  await assertFails(unauthDb.collection('device_pubkeys').doc('stu_uid_1_HWFP01').get());

  // 2. dpop_nonces: strictly server-only (Admin SDK bypasses rules; all clients denied)
  await assertFails(student1Db.collection('dpop_nonces').doc('jti_001').set({ jti: 'jti_001' }));
  await assertFails(adminDb.collection('dpop_nonces').doc('jti_001').set({ jti: 'jti_001' }));
  await assertFails(adminDb.collection('dpop_nonces').doc('jti_001').get());

  // 3. examSessions: student cannot write/forge session; staff/admin can write; owner student can read own
  await assertFails(
    student1Db.collection('examSessions').doc('exm_01_stu_uid_1').set({
      examId: 'exm_01',
      studentUid: 'stu_uid_1',
      sessionNonce: 'forged_nonce',
      submitted: false,
    })
  );
  await assertSucceeds(
    adminDb.collection('examSessions').doc('exm_01_stu_uid_1').set({
      examId: 'exm_01',
      studentUid: 'stu_uid_1',
      sessionNonce: 'server_nonce_abc',
      submitted: false,
    })
  );
  await assertSucceeds(student1Db.collection('examSessions').doc('exm_01_stu_uid_1').get());
  await assertFails(student2Db.collection('examSessions').doc('exm_01_stu_uid_1').get());
  await assertFails(unauthDb.collection('examSessions').doc('exm_01_stu_uid_1').get());

  // 4. revoked_devices: Admin writes & lists; signed-in user can get own deviceFp doc; student cannot write or list; unauth denied
  await assertSucceeds(
    adminDb.collection('revoked_devices').doc('HW_COMPROMISED_01').set({
      deviceFingerprint: 'HW_COMPROMISED_01',
      revoked: true,
      reason: 'Stolen device',
    })
  );
  await assertSucceeds(adminDb.collection('revoked_devices').get());
  await assertSucceeds(student1Db.collection('revoked_devices').doc('HW_COMPROMISED_01').get());
  await assertFails(student1Db.collection('revoked_devices').get());
  await assertFails(
    student1Db.collection('revoked_devices').doc('HW_COMPROMISED_01').update({ revoked: false })
  );
  await assertFails(unauthDb.collection('revoked_devices').doc('HW_COMPROMISED_01').get());

  // 5. server_audit_hash_chain & server_audit_chain_meta: Admin read-only, ALL client writes denied
  await assertSucceeds(adminDb.collection('server_audit_hash_chain').get());
  await assertSucceeds(adminDb.collection('server_audit_chain_meta').doc('head').get());
  await assertFails(
    adminDb.collection('server_audit_hash_chain').doc('0000000001').set({ seq: 1, currentHash: 'forged' })
  );
  await assertFails(
    adminDb.collection('server_audit_chain_meta').doc('head').set({ seq: 1, currentHash: 'forged' })
  );
  await assertFails(student1Db.collection('server_audit_hash_chain').get());
  await assertFails(unauthDb.collection('server_audit_chain_meta').doc('head').get());
});

test('11. Spark-Plan Native Firebase Auth: waqar@portal.waqarinstitute.in Admin bootstrap & one-time student examResults write', async () => {
  const waqarSparkAdminDb = testEnv.authenticatedContext('waqar_spark_uid', {
    email: 'waqar@portal.waqarinstitute.in',
    email_verified: false,
  }).firestore(DATABASE_ID);

  const studentSparkDb = testEnv.authenticatedContext('stu_spark_uid', {
    email: 'aarav@portal.waqarinstitute.in',
    email_verified: false,
  }).firestore(DATABASE_ID);

  const now = new Date(Date.now() - 2000);

  // 1. waqar@portal.waqarinstitute.in can immediately write /users/{uid} and /portalCredentials/waqar on first login
  await assertSucceeds(
    waqarSparkAdminDb.collection('users').doc('waqar_spark_uid').set({
      uid: 'waqar_spark_uid',
      email: 'waqar@portal.waqarinstitute.in',
      displayName: 'Waqar Khan (Admin)',
      role: 'admin',
      username: 'waqar',
      phone: '',
      mfaEnabled: false,
      totpSecret: '',
      linkedEntityId: 'ADMIN_ROOT',
      isAdmin: true,
      portalRoles: ['admin'],
      createdAt: now,
    })
  );

  await assertSucceeds(
    waqarSparkAdminDb.collection('portalCredentials').doc('waqar').set({
      username: 'waqar',
      passwordHash: '3a7bd3e2360a3d29eea436fcfb7e44c735d117c42d1c1835420b6b9942dd4f1b',
      passwordSalt: '00112233445566778899aabbccddeeff',
      role: 'admin',
      linkedEntityId: 'ADMIN_ROOT',
      displayName: 'Waqar Khan (Admin)',
      assignedByUid: 'waqar_spark_uid',
      active: true,
      isAdmin: true,
      portalRoles: ['admin'],
      createdAt: now,
    })
  );

  // 2. Admin provisions student /users/{stu_spark_uid} with linkedEntityId = 'stu_spark_001'
  await assertSucceeds(
    waqarSparkAdminDb.collection('users').doc('stu_spark_uid').set({
      uid: 'stu_spark_uid',
      email: 'aarav@portal.waqarinstitute.in',
      displayName: 'Aarav Spark',
      role: 'student',
      username: 'aarav',
      phone: '',
      mfaEnabled: false,
      totpSecret: '',
      linkedEntityId: 'stu_spark_001',
      isStudent: true,
      isTeacher: false,
      isParent: false,
      isAdmin: false,
      portalRoles: ['student'],
      createdAt: now,
    })
  );

  // Student CANNOT self-escalate role to teacher or admin
  await assertFails(
    studentSparkDb.collection('users').doc('stu_spark_uid').update({
      role: 'teacher',
      isTeacher: true,
    })
  );

  // 3. Student CAN create own /examResults/{examId}_{uid} ONCE
  const validSubmission = {
    id: 'exm_spark_01_stu_spark_uid',
    examId: 'exm_spark_01',
    examTitle: 'ADCA Spark CBT',
    studentId: 'stu_spark_001',
    studentName: 'Aarav Spark',
    marksObtained: 88,
    totalMarks: 100,
    grade: 'A',
    rank: 1,
    ownerUid: 'stu_spark_uid',
    createdAt: now,
  };
  await assertSucceeds(
    studentSparkDb.collection('examResults').doc('exm_spark_01_stu_spark_uid').set(validSubmission)
  );

  // Student CANNOT update or overwrite their submitted examResult
  await assertFails(
    studentSparkDb.collection('examResults').doc('exm_spark_01_stu_spark_uid').update({
      marksObtained: 100,
      grade: 'A+',
    })
  );

  // Student CANNOT write another student's examResult doc
  await assertFails(
    studentSparkDb.collection('examResults').doc('exm_spark_01_other_uid').set({
      ...validSubmission,
      id: 'exm_spark_01_other_uid',
    })
  );
});
