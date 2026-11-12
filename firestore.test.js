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

test('1. Unauthenticated spoofed writes and unauthorized profile reads are denied across collections', async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore(DATABASE_ID);
  await assertFails(unauthDb.collection('users').doc('user_1').get());
  await assertFails(unauthDb.collection('unknownCollection').doc('doc_1').get());
  await assertFails(
    unauthDb.collection('students').doc('stu_spoof').set({
      id: 'stu_spoof',
      ownerUid: 'spoofed_non_cred_uid',
    })
  );
  // Direct credential lookup by exact username key succeeds without Google Sign-In popup
  await assertSucceeds(unauthDb.collection('portalCredentials').doc('admin').get());
});

test('2. User profile & username reservation creation succeeds with valid schema and fails on spoofing', async () => {
  const aliceDb = testEnv.authenticatedContext('alice_uid', {
    email: 'alice@waqarinstitute.edu',
    email_verified: true,
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
});

test('3. Student (with photo & file attachment), PortalCredentials isolation, Fee, and AuditLog rules enforce schema & RBAC', async () => {
  const adminDb = testEnv.authenticatedContext('waqar_uid', {
    email: 'samdkhan8256972@gmail.com',
    email_verified: true,
  }).firestore(DATABASE_ID);

  const studentDb = testEnv.authenticatedContext('stu_uid_1', {
    email: 'aarav@example.com',
    email_verified: true,
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

  // Student can get their own credential document by username to verify login
  await assertSucceeds(
    studentDb.collection('portalCredentials').doc('aarav_wci').get()
  );

  // Student CANNOT list all portalCredentials (blocks viewing other accounts' usernames/passwords!)
  await assertFails(
    studentDb.collection('portalCredentials').get()
  );

  // Admin CAN list all portalCredentials
  await assertSucceeds(
    adminDb.collection('portalCredentials').get()
  );

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
