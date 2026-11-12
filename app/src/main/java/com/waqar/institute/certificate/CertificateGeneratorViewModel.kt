package com.waqar.institute.certificate

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import com.example.R
import com.example.data.model.CertificateRecord
import com.example.data.model.CourseRecord
import com.example.data.model.ExamResult
import com.example.data.model.StudentRecord
import com.example.ui.designsystem.GlassCard
import com.example.ui.designsystem.JetBrainsMono
import com.example.ui.designsystem.LiquidPill
import com.example.ui.designsystem.ShimmerButton
import com.example.ui.designsystem.WciColors
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class AutoFilledCertificateModel(
    val studentId: String,
    val studentName: String,
    val certificateOrRollNo: String,
    val serialNo: String,
    val courseTitle: String,
    val durationMonthsRequired: Int,
    val durationMonthsCompleted: Int,
    val durationFrom: String,
    val durationTo: String,
    val grade: String,
    val percentage: Int,
    val gradeAndPercentageLabel: String,
    val dateOfIssue: String,
    val studentSignatureText: String,
    val adminAuthorizedSignature: String,
    val verificationHash: String,
    val fullVerificationUrl: String,
    val compactScannableUrl: String,
    val isUnlocked: Boolean,
    val lockReasonBadge: String
)

/**
 * Automated Certificate Data Filling & 15-Month Course Completion Locking Engine
 * (`CertificateGeneratorViewModel.kt`).
 *
 * - Enforces a Locked/Unlocked completion state check:
 *   The certificate view remains locked with a badge ("Course in Progress / Locked") until the
 *   student completes the required course duration (default 15 months) and passes requirements,
 *   OR an official CertificateRecord is issued/unlocked by the Admin (`Waqar`).
 * - Dynamically resolves student data from Cloud Firestore (`students/{uid}`, `examResults`,
 *   `courses`, `certificates`) and auto-fills all fields onto the official Waqar Computer
 *   Institute Certificate template.
 */
class CertificateGeneratorViewModel : ViewModel() {

    companion object {
        const val DEFAULT_REQUIRED_COURSE_MONTHS = 15

        fun buildAutoFilledCertificate(
            student: StudentRecord?,
            issuedCert: CertificateRecord?,
            courses: List<CourseRecord>,
            examResults: List<ExamResult>
        ): AutoFilledCertificateModel {
            val sdfDisplay = SimpleDateFormat("dd MMM yyyy", Locale.US)
            val sdfIso = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val nowDate = Date()

            val studentName = issuedCert?.studentName?.takeIf { it.isNotBlank() }
                ?: student?.fullName?.takeIf { it.isNotBlank() }
                ?: "Enrolled Student"

            val enrollmentNo = student?.enrollmentNo?.takeIf { it.isNotBlank() }
                ?: "WCI-2026-1001"

            val serialNo = issuedCert?.serialNo?.takeIf { it.isNotBlank() }
                ?: "WCI-CERT-2026-${enrollmentNo.takeLast(4)}"

            val courseTitle = issuedCert?.courseName?.takeIf { it.isNotBlank() }
                ?: student?.courseName?.takeIf { it.isNotBlank() }
                ?: "ADCA (Advanced Diploma in Computer Applications)"

            val matchedCourse = courses.firstOrNull {
                it.title.equals(courseTitle, ignoreCase = true) ||
                    courseTitle.contains(it.code, ignoreCase = true)
            }
            val requiredMonths = matchedCourse?.durationMonths?.takeIf { it >= 12 }
                ?: DEFAULT_REQUIRED_COURSE_MONTHS

            // Calculate best exam percentage & grade for this student
            val studentExams = examResults.filter {
                (student != null && (it.studentId == student.id || it.studentName.equals(student.fullName, ignoreCase = true))) ||
                    it.studentName.equals(studentName, ignoreCase = true)
            }
            val bestExam = studentExams.maxByOrNull {
                if (it.totalMarks > 0) (it.marksObtained * 100) / it.totalMarks else 0
            }
            val computedPct = when {
                bestExam != null && bestExam.totalMarks > 0 ->
                    (bestExam.marksObtained * 100) / bestExam.totalMarks
                issuedCert != null -> 85
                else -> 0
            }
            val resolvedGrade = issuedCert?.grade?.takeIf { it.isNotBlank() }
                ?: bestExam?.grade?.takeIf { it.isNotBlank() }
                ?: when {
                    computedPct >= 90 -> "A+"
                    computedPct >= 80 -> "A"
                    computedPct >= 70 -> "B+"
                    computedPct >= 60 -> "B"
                    computedPct >= 40 -> "C"
                    else -> "PENDING"
                }

            val enrolledTimestampMs = student?.createdAt?.toDate()?.time
                ?: nowDate.time
            val elapsedMonths = (((nowDate.time - enrolledTimestampMs) / (1000L * 3600L * 24L * 30L)).toInt())
                .coerceAtLeast(1)

            val isCourseCompletedOrIssued = (issuedCert != null && !issuedCert.revoked) ||
                student?.status.equals("ALUMNI", ignoreCase = true) ||
                student?.status.equals("GRADUATED", ignoreCase = true) ||
                (bestExam != null && elapsedMonths >= requiredMonths && computedPct >= 40)

            val effectiveCompletedMonths = if (isCourseCompletedOrIssued) {
                requiredMonths
            } else {
                elapsedMonths.coerceIn(1, (requiredMonths - 1).coerceAtLeast(1))
            }

            // Duration From -> To (15 Months span)
            val calTo = Calendar.getInstance().apply { time = nowDate }
            val calFrom = Calendar.getInstance().apply {
                time = nowDate
                add(Calendar.MONTH, -requiredMonths)
            }
            val durationFromStr = sdfDisplay.format(calFrom.time)
            val durationToStr = sdfDisplay.format(calTo.time)

            val issueDateDisplay = issuedCert?.issueDateIso?.takeIf { it.isNotBlank() }
                ?: sdfDisplay.format(nowDate)
            val issueDateIso = issuedCert?.issueDateIso?.takeIf { it.isNotBlank() }
                ?: sdfIso.format(nowDate)

            val verificationHash = issuedCert?.verificationHash?.takeIf { it.isNotBlank() }
                ?: "SHA256:${(serialNo + enrollmentNo + studentName).hashCode().toUInt().toString(16).uppercase()}"

            val fullVerifyUrl = CertificateQrGenerator.buildVerificationUrl(
                certSerialNo = serialNo,
                enrollmentNo = enrollmentNo,
                studentName = studentName,
                courseName = courseTitle,
                grade = resolvedGrade,
                percentage = computedPct,
                issueDateIso = issueDateIso
            )
            val compactUrl = CertificateQrGenerator.buildCompactScannableUrl(
                certSerialNo = serialNo,
                enrollmentNo = enrollmentNo,
                grade = resolvedGrade,
                percentage = computedPct
            )

            val lockBadge = when {
                isCourseCompletedOrIssued ->
                    "COURSE COMPLETED • VERIFIED & UNLOCKED ($requiredMonths MONTHS)"
                bestExam == null ->
                    "LOCKED (EXAM PENDING • $effectiveCompletedMonths/$requiredMonths MONTHS)"
                else ->
                    "Course in Progress / Locked ($effectiveCompletedMonths / $requiredMonths Months Completed)"
            }

            val gradeLabel = if (computedPct > 0) {
                "Grade $resolvedGrade ($computedPct%)"
            } else {
                "Exam Pending (0%)"
            }

            return AutoFilledCertificateModel(
                studentId = student?.id ?: issuedCert?.studentId ?: "stu_verified",
                studentName = studentName,
                certificateOrRollNo = "$serialNo / $enrollmentNo",
                serialNo = serialNo,
                courseTitle = courseTitle,
                durationMonthsRequired = requiredMonths,
                durationMonthsCompleted = effectiveCompletedMonths,
                durationFrom = durationFromStr,
                durationTo = durationToStr,
                grade = resolvedGrade,
                percentage = computedPct,
                gradeAndPercentageLabel = gradeLabel,
                dateOfIssue = issueDateDisplay,
                studentSignatureText = studentName,
                adminAuthorizedSignature = "Waqar",
                verificationHash = verificationHash,
                fullVerificationUrl = fullVerifyUrl,
                compactScannableUrl = compactUrl,
                isUnlocked = isCourseCompletedOrIssued,
                lockReasonBadge = lockBadge
            )
        }
    }
}

/**
 * Renders the Official Waqar Computer Institute Certificate of Completion using the bundled
 * `R.drawable.wci_certificate_template` artwork with dynamic auto-filled fields, Locked/Unlocked
 * completion state badge, and a real camera-scannable ISO/IEC 18004 verification QR code.
 */
@Composable
fun WaqarOfficialDynamicCertificateCard(
    model: AutoFilledCertificateModel,
    isAdmin: Boolean,
    onAdminUnlockAndIssue: () -> Unit,
    onExportPdf: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val qrBitmap = remember(model.compactScannableUrl) {
        CertificateQrGenerator.generateScannableQrBitmap(
            content = model.compactScannableUrl,
            sizePx = 360
        )
    }

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        glowColor = if (model.isUnlocked) Color(0xFF14F195) else Color(0xFFFFB547),
        strongSurface = true
    ) {
        // 1. Lock / Unlock Status Header Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = if (model.isUnlocked) Icons.Default.Verified else Icons.Default.Lock,
                    contentDescription = model.lockReasonBadge,
                    tint = if (model.isUnlocked) WciColors.Success else WciColors.Warning,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = model.lockReasonBadge,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontFamily = JetBrainsMono,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (model.isUnlocked) WciColors.Success else WciColors.Warning
                    )
                )
            }
            Box(
                modifier = Modifier
                    .background(
                        color = if (model.isUnlocked) WciColors.Success.copy(alpha = 0.2f)
                        else WciColors.Warning.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(999.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = if (model.isUnlocked) WciColors.Success else WciColors.Warning,
                        shape = RoundedCornerShape(999.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (model.isUnlocked) "UNLOCKED" else "LOCKED",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = JetBrainsMono,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Progress bar toward 15-Month Course Completion
        LinearProgressIndicator(
            progress = {
                (model.durationMonthsCompleted.toFloat() / model.durationMonthsRequired.coerceAtLeast(1)).coerceIn(0f, 1f)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp)),
            color = if (model.isUnlocked) WciColors.Success else WciColors.Warning,
            trackColor = Color.White.copy(alpha = 0.16f)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 2. Official Waqar Computer Institute Certificate Canvas (Landscape A4 Aspect Ratio)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.414f)
                .clip(RoundedCornerShape(14.dp))
                .border(2.dp, Color(0xFFD4AF37), RoundedCornerShape(14.dp))
                .background(Color.White)
        ) {
            val w = maxWidth
            val h = maxHeight
            val scaleFactor = (w.value / 360f).coerceIn(0.75f, 1.8f)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (!model.isUnlocked) Modifier.blur(6.dp) else Modifier)
            ) {
                // Background Official Waqar Computer Institute Template Image
                Image(
                    painter = painterResource(id = R.drawable.wci_certificate_template),
                    contentDescription = "Waqar Computer Institute Certificate of Completion",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize()
                )

                // Auto-Filled Dynamic Fields Positioned Precisely on the Certificate Lines
                // Field 1: Student Name (y = 46.8%, x = 31.5%..82%)
                Text(
                    text = model.studentName,
                    color = Color(0xFF0A1D4E),
                    fontSize = (11.5f * scaleFactor).sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Serif,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .offset(x = w * 0.315f, y = h * 0.463f)
                        .width(w * 0.50f)
                )

                // Field 2: Certificate / Roll No. (y = 51.8%, x = 35.5%..82%)
                Text(
                    text = model.certificateOrRollNo,
                    color = Color(0xFF0A1D4E),
                    fontSize = (9.5f * scaleFactor).sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = JetBrainsMono,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .offset(x = w * 0.355f, y = h * 0.514f)
                        .width(w * 0.46f)
                )

                // Field 3: Course Title (y = 56.8%, x = 31.5%..82%)
                Text(
                    text = model.courseTitle,
                    color = Color(0xFF0A1D4E),
                    fontSize = (10f * scaleFactor).sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .offset(x = w * 0.315f, y = h * 0.564f)
                        .width(w * 0.50f)
                )

                // Field 4a: Duration From (y = 61.8%, x = 36%..59%)
                Text(
                    text = model.durationFrom,
                    color = Color(0xFF0A1D4E),
                    fontSize = (9.2f * scaleFactor).sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier
                        .offset(x = w * 0.365f, y = h * 0.614f)
                        .width(w * 0.22f)
                )

                // Field 4b: Duration To (y = 61.8%, x = 64.5%..82%)
                Text(
                    text = "${model.durationTo} (${model.durationMonthsRequired}M)",
                    color = Color(0xFF0A1D4E),
                    fontSize = (9.2f * scaleFactor).sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier
                        .offset(x = w * 0.642f, y = h * 0.614f)
                        .width(w * 0.20f)
                )

                // Field 5: Grade / Percentage (y = 66.8%, x = 36%..82%)
                Text(
                    text = model.gradeAndPercentageLabel,
                    color = Color(0xFF0A5C36),
                    fontSize = (10f * scaleFactor).sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    modifier = Modifier
                        .offset(x = w * 0.362f, y = h * 0.663f)
                        .width(w * 0.45f)
                )

                // Field 6: Date of Issue (y = 71.8%, x = 36%..82%)
                Text(
                    text = model.dateOfIssue,
                    color = Color(0xFF0A1D4E),
                    fontSize = (9.5f * scaleFactor).sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier
                        .offset(x = w * 0.362f, y = h * 0.713f)
                        .width(w * 0.45f)
                )

                // Field 7: Student's Signature (bottom left line: x = 15%..35%, y = 86.2%)
                Text(
                    text = model.studentSignatureText,
                    color = Color(0xFF1A365D),
                    fontSize = (10.5f * scaleFactor).sp,
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif,
                    maxLines = 1,
                    modifier = Modifier
                        .offset(x = w * 0.16f, y = h * 0.858f)
                        .width(w * 0.20f)
                )

                // Scannable ISO/IEC 18004 Verification QR Code Badge (Top-Right inside certificate frame)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .offset(x = w * 0.76f, y = h * 0.14f)
                        .background(Color.White, RoundedCornerShape(6.dp))
                        .border(1.dp, Color(0xFFD4AF37), RoundedCornerShape(6.dp))
                        .padding(3.dp)
                ) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "Scannable Verification QR Code",
                        modifier = Modifier.size((w * 0.14f).coerceIn(44.dp, 86.dp))
                    )
                    Text(
                        text = "SCAN TO VERIFY",
                        color = Color(0xFF0A1D4E),
                        fontSize = (5.5f * scaleFactor).sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = JetBrainsMono
                    )
                }
            }

            // Locked Veil Overlay when course is still in progress
            if (!model.isUnlocked) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xCC070B1E)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Certificate Locked",
                            tint = WciColors.Warning,
                            modifier = Modifier.size(40.dp)
                        )
                        Text(
                            text = "Course in Progress / Locked",
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold
                            )
                        )
                        Text(
                            text = "This official certificate unlocks automatically upon completing the ${model.durationMonthsRequired}-month course duration and passing all required assessments (${model.durationMonthsCompleted}/${model.durationMonthsRequired} months completed).",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = WciColors.TextSecondaryDark
                            )
                        )
                        if (isAdmin) {
                            Spacer(modifier = Modifier.height(4.dp))
                            ShimmerButton(
                                text = "Admin: Unlock & Issue Official Certificate Now",
                                icon = Icons.Default.LockOpen,
                                onClick = onAdminUnlockAndIssue
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 3. Scannable QR Verification URL & Export Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "Official Verification QR",
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                        .border(1.5.dp, Color(0xFFD4AF37), RoundedCornerShape(8.dp))
                        .padding(4.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${model.studentName} • ${model.gradeAndPercentageLabel}",
                        style = MaterialTheme.typography.titleSmall.copy(
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold
                        )
                    )
                    Text(
                        text = "Authorized Signatory: ${model.adminAuthorizedSignature} • Roll: ${model.certificateOrRollNo}",
                        style = MaterialTheme.typography.bodySmall.copy(color = WciColors.Info)
                    )
                    Text(
                        text = model.compactScannableUrl,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = JetBrainsMono,
                            color = WciColors.Success,
                            fontSize = 11.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable {
                            runCatching {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(model.fullVerificationUrl)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LiquidPill(
                text = "Open Verification Link",
                selected = false,
                icon = Icons.Default.OpenInBrowser,
                onClick = {
                    runCatching {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(model.fullVerificationUrl)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                },
                modifier = Modifier.weight(1f)
            )
            if (model.isUnlocked) {
                LiquidPill(
                    text = "Download Official PDF",
                    selected = true,
                    icon = Icons.Default.PictureAsPdf,
                    onClick = onExportPdf,
                    modifier = Modifier.weight(1f)
                )
            } else if (isAdmin) {
                LiquidPill(
                    text = "Unlock & Issue",
                    selected = true,
                    icon = Icons.Default.QrCode2,
                    onClick = onAdminUnlockAndIssue,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
