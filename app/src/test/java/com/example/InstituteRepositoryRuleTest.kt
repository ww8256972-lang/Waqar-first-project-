package com.example

import com.example.auth.FirebaseAuthServiceProvider
import com.example.data.firestore.FirestoreOperationException
import com.example.data.firestore.InstituteRepository
import com.example.data.firestore.isPermissionDenied
import com.example.data.model.CourseRecord
import com.example.data.model.FeeInvoice
import com.example.data.model.PortalCredential
import com.example.data.model.SecurityAuditLog
import com.example.data.model.StudentRecord
import com.example.data.model.UserProfile
import com.google.firebase.Timestamp
import java.util.Date
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
class InstituteRepositoryRuleTest : FirestoreEmulatorTestBase() {

    private lateinit var repository: InstituteRepository

    @Before
    fun setUp() {
        repository = InstituteRepository(db, auth)
    }

    @Test
    fun saveAndReadUserProfile_and_PortalCredential_and_StudentWithAttachments_succeedsForOwner() = runBlocking {
        val uid = signInTestUser("waqar@waqarinstitute.edu")
        val safeTimestamp = Timestamp(Date(System.currentTimeMillis() - 5000))

        val profile = UserProfile(
            uid = uid,
            email = "waqar@waqarinstitute.edu",
            displayName = "Waqar Khan",
            role = "admin",
            username = "waqar_admin",
            phone = "+919876543210",
            mfaEnabled = true,
            totpSecret = "JBSWY3DPEHPK3PXP",
            linkedEntityId = "staff_001",
            createdAt = safeTimestamp
        )
        repository.saveUserProfile(profile)

        val fetchedProfile = repository.getUserProfile(uid)
        assertNotNull(fetchedProfile)
        assertEquals("Waqar Khan", fetchedProfile?.displayName)
        assertEquals("admin", fetchedProfile?.role)

        val course = CourseRecord(
            id = "crs_adca",
            code = "ADCA-101",
            title = "Advanced Diploma in Computer Applications",
            category = "Diploma",
            durationMonths = 12,
            totalFeePaise = 1800000L,
            syllabusSummary = "Fundamentals, Tally Prime, Python, Web Design, AI Tools",
            totalModules = 8,
            active = true,
            ownerUid = uid,
            createdAt = safeTimestamp
        )
        repository.saveCourse(course)

        val student = StudentRecord(
            id = "stu_1001",
            enrollmentNo = "WCI-2026-1001",
            fullName = "Zoya Khan",
            email = "zoya@example.com",
            phone = "+919800011122",
            guardianName = "Imran Khan",
            guardianPhone = "+919800011123",
            courseId = "crs_adca",
            courseName = "Advanced Diploma in Computer Applications",
            batchId = "bat_adca_m1",
            batchName = "ADCA Morning 9AM",
            status = "ACTIVE",
            totalFeePaise = 1800000L,
            paidFeePaise = 900000L,
            attendancePercent = 96,
            photoUri = "data:image/jpeg;base64,/9j/4AAQSkZJRg==",
            attachmentName = "Zoya_Aadhar_Marksheet.pdf (128 KB)",
            ownerUid = uid,
            createdAt = safeTimestamp
        )
        repository.saveStudent(student)

        // Verify PBKDF2-HMAC-SHA256 PortalCredential save & get
        val salt = FirebaseAuthServiceProvider.generateSaltHex()
        val hash = FirebaseAuthServiceProvider.hashPasswordPbkdf2("Zoya@2026".toCharArray(), salt)
        val credential = PortalCredential(
            username = "zoya_wci",
            passwordHash = hash,
            passwordSalt = salt,
            role = "student",
            linkedEntityId = "stu_1001",
            displayName = "Zoya Khan",
            assignedByUid = uid,
            active = true,
            createdAt = safeTimestamp
        )
        repository.savePortalCredential(credential)

        val fetchedCredential = repository.getPortalCredential("zoya_wci")
        assertNotNull(fetchedCredential)
        assertEquals("stu_1001", fetchedCredential?.linkedEntityId)
        assertTrue(
            FirebaseAuthServiceProvider.verifyPasswordConstantTime(
                "Zoya@2026".toCharArray(),
                fetchedCredential!!.passwordSalt,
                fetchedCredential.passwordHash
            )
        )

        val invoice = FeeInvoice(
            id = "fee_1001",
            receiptNo = "RCPT-2026-1001",
            studentId = "stu_1001",
            studentName = "Zoya Khan",
            courseName = "Advanced Diploma in Computer Applications",
            title = "Semester 1 Fee Receipt",
            totalAmountPaise = 1800000L,
            paidAmountPaise = 900000L,
            discountPaise = 0L,
            finePaise = 0L,
            paymentMode = "UPI",
            status = "PARTIAL",
            dueDateIso = "2026-12-15",
            paidAtIso = "2026-11-10",
            ownerUid = uid,
            createdAt = safeTimestamp
        )
        repository.saveFeeInvoice(invoice)

        val auditLog = SecurityAuditLog(
            id = "aud_1001",
            actorUid = uid,
            actorEmail = "waqar@waqarinstitute.edu",
            action = "ENROLL_STUDENT",
            module = "M03_STUDENTS",
            targetId = "stu_1001",
            severity = "INFO",
            details = "Created student Zoya Khan and receipt RCPT-2026-1001",
            createdAt = safeTimestamp
        )
        repository.logAuditEvent(auditLog)
    }

    @Test
    fun saveStudent_withMismatchedOwnerUidForNonStaff_isDeniedByRules() = runBlocking {
        val aliceUid = signInTestUser("student1@waqarinstitute.edu")
        val safeTimestamp = Timestamp(Date(System.currentTimeMillis() - 5000))

        // Alice is a regular student (not admin/teacher), trying to spoof ownerUid = "other_uid"
        val spoofedStudent = StudentRecord(
            id = "stu_spoof",
            enrollmentNo = "WCI-SPOOF",
            fullName = "Spoofed Student",
            email = "spoof@example.com",
            phone = "+919000000000",
            guardianName = "Parent",
            guardianPhone = "+919000000001",
            courseId = "crs_1",
            courseName = "DCA",
            batchId = "bat_1",
            batchName = "Batch 1",
            status = "ACTIVE",
            totalFeePaise = 100000L,
            paidFeePaise = 0L,
            attendancePercent = 100,
            photoUri = "",
            attachmentName = "",
            ownerUid = "someone_else_uid",
            createdAt = safeTimestamp
        )

        try {
            repository.saveStudent(spoofedStudent)
            fail("Expected FirestoreOperationException (PERMISSION_DENIED) when spoofing ownerUid")
        } catch (e: FirestoreOperationException) {
            assertTrue("Expected permission denied cause, got: ${e.message}", e.isPermissionDenied())
            assertNotNull(aliceUid)
        }
    }
}
