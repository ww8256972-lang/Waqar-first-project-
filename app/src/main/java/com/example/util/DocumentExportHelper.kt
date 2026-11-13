package com.example.util

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.R
import com.example.data.model.CertificateRecord
import com.example.data.model.ExamResult
import com.example.data.model.FeeInvoice
import com.example.data.model.PayrollEntry
import com.example.data.model.StudentRecord
import com.example.ui.designsystem.formatInrPaise
import com.waqar.institute.certificate.AutoFilledCertificateModel
import com.waqar.institute.certificate.CertificateQrGenerator
import java.io.File
import java.io.FileOutputStream

object DocumentExportHelper {

    fun exportAndShareFeeReceiptPdf(
        context: Context,
        instituteName: String,
        gstNumber: String,
        invoice: FeeInvoice
    ) {
        val pdf = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = pdf.startPage(pageInfo)
        val canvas = page.canvas

        drawHeader(canvas, instituteName, "OFFICIAL FEE RECEIPT • GSTIN: $gstNumber")

        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 22, 43)
            textSize = 13f
        }
        val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 22, 43)
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        var y = 155f
        val lines = listOf(
            "Receipt Number" to invoice.receiptNo,
            "Student Name" to invoice.studentName,
            "Student ID" to invoice.studentId,
            "Course Enrolled" to invoice.courseName,
            "Particulars" to invoice.title,
            "Invoice Total" to formatInrPaise(invoice.totalAmountPaise),
            "Discount Applied" to formatInrPaise(invoice.discountPaise),
            "Late Fine" to formatInrPaise(invoice.finePaise),
            "Amount Paid" to formatInrPaise(invoice.paidAmountPaise),
            "Balance Due" to formatInrPaise(invoice.balancePaise),
            "Payment Mode" to invoice.paymentMode,
            "Payment Status" to invoice.status,
            "Due Date" to invoice.dueDateIso,
            "Paid Date" to invoice.paidAtIso.ifBlank { "—" }
        )

        lines.forEach { (label, value) ->
            canvas.drawText(label, 50f, y, bodyPaint)
            canvas.drawText(value, 260f, y, boldPaint)
            y += 30f
        }

        drawFooter(canvas, "VERIFIED DIGITAL FEE RECEIPT • ${invoice.receiptNo}")
        pdf.finishPage(page)

        saveAndShareFile(
            context = context,
            fileName = "FeeReceipt_${invoice.receiptNo}.pdf",
            mimeType = "application/pdf"
        ) { out -> pdf.writeTo(out) }
        pdf.close()
    }

    fun exportAndShareCertificatePdf(
        context: Context,
        instituteName: String,
        cert: CertificateRecord,
        enrollmentNo: String = "",
        percentage: Int = -1
    ) {
        val resolvedRoll = enrollmentNo.ifBlank { cert.studentId }.ifBlank { cert.serialNo }
        val resolvedPct = if (percentage >= 0) {
            percentage.coerceIn(0, 100)
        } else {
            when (cert.grade.trim().uppercase()) {
                "A+" -> 95
                "A" -> 85
                "B+" -> 75
                "B" -> 65
                "C" -> 50
                else -> 80
            }
        }
        val compactUrl = CertificateQrGenerator.buildCompactScannableUrl(
            certSerialNo = cert.serialNo,
            enrollmentNo = resolvedRoll,
            grade = cert.grade,
            percentage = resolvedPct
        )
        exportOfficialTemplatePdf(
            context = context,
            studentName = cert.studentName,
            certificateOrRollNo = "${cert.serialNo} / $resolvedRoll",
            serialNo = cert.serialNo,
            courseTitle = cert.courseName,
            durationFrom = "Enrolled Term",
            durationTo = "${cert.issueDateIso.ifBlank { "Issued" }} (Completed)",
            gradeAndPercentage = "Grade ${cert.grade} ($resolvedPct%)",
            dateOfIssue = cert.issueDateIso.ifBlank { "Verified" },
            studentSignature = cert.studentName,
            compactScannableUrl = compactUrl
        )
    }

    fun exportAndShareOfficialAutoFilledCertificatePdf(
        context: Context,
        model: AutoFilledCertificateModel
    ) {
        exportOfficialTemplatePdf(
            context = context,
            studentName = model.studentName,
            certificateOrRollNo = model.certificateOrRollNo,
            serialNo = model.serialNo,
            courseTitle = model.courseTitle,
            durationFrom = model.durationFrom,
            durationTo = "${model.durationTo} (${model.durationMonthsRequired}M)",
            gradeAndPercentage = model.gradeAndPercentageLabel,
            dateOfIssue = model.dateOfIssue,
            studentSignature = model.studentSignatureText,
            compactScannableUrl = model.compactScannableUrl
        )
    }

    private fun exportOfficialTemplatePdf(
        context: Context,
        studentName: String,
        certificateOrRollNo: String,
        serialNo: String,
        courseTitle: String,
        durationFrom: String,
        durationTo: String,
        gradeAndPercentage: String,
        dateOfIssue: String,
        studentSignature: String,
        compactScannableUrl: String
    ) {
        val pdf = PdfDocument()
        val pageWidth = 842
        val pageHeight = 595
        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val page = pdf.startPage(pageInfo)
        val canvas = page.canvas

        // 1. Draw Official Waqar Computer Institute Certificate Template Bitmap
        runCatching {
            val templateBitmap = BitmapFactory.decodeResource(
                context.resources,
                R.drawable.wci_certificate_template
            )
            if (templateBitmap != null) {
                canvas.drawBitmap(
                    templateBitmap,
                    null,
                    Rect(0, 0, pageWidth, pageHeight),
                    Paint(Paint.FILTER_BITMAP_FLAG)
                )
            }
        }

        // 2. Auto-Fill Student Data onto the Official Certificate Lines
        val fieldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(10, 29, 78)
            textSize = 15f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(10, 29, 78)
            textSize = 13.5f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val gradePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(10, 92, 54)
            textSize = 14.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val sigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(26, 54, 93)
            textSize = 15f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
        }

        canvas.drawText(studentName, pageWidth * 0.315f, pageHeight * 0.485f, fieldPaint)
        canvas.drawText(certificateOrRollNo, pageWidth * 0.355f, pageHeight * 0.535f, monoPaint)
        canvas.drawText(courseTitle, pageWidth * 0.315f, pageHeight * 0.585f, fieldPaint)
        canvas.drawText(durationFrom, pageWidth * 0.365f, pageHeight * 0.635f, fieldPaint)
        canvas.drawText(durationTo, pageWidth * 0.642f, pageHeight * 0.635f, fieldPaint)
        canvas.drawText(gradeAndPercentage, pageWidth * 0.362f, pageHeight * 0.684f, gradePaint)
        canvas.drawText(dateOfIssue, pageWidth * 0.362f, pageHeight * 0.734f, fieldPaint)
        canvas.drawText(studentSignature, pageWidth * 0.165f, pageHeight * 0.878f, sigPaint)

        // 3. Draw Scannable ISO/IEC 18004 Verification QR Code in Top-Right Frame
        runCatching {
            val qrBitmap = CertificateQrGenerator.generateScannableQrBitmap(
                content = compactScannableUrl,
                sizePx = 280
            )
            val qrLeft = (pageWidth * 0.77f).toInt()
            val qrTop = (pageHeight * 0.14f).toInt()
            val qrSize = 96
            canvas.drawBitmap(
                qrBitmap,
                null,
                Rect(qrLeft, qrTop, qrLeft + qrSize, qrTop + qrSize),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
        }

        pdf.finishPage(page)
        saveAndShareFile(
            context = context,
            fileName = "WCI_Certificate_${serialNo.replace(" ", "_")}.pdf",
            mimeType = "application/pdf"
        ) { out -> pdf.writeTo(out) }
        pdf.close()
    }

    fun exportAndShareMarkSheetPdf(
        context: Context,
        instituteName: String,
        result: ExamResult
    ) {
        val pdf = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = pdf.startPage(pageInfo)
        val canvas = page.canvas

        drawHeader(canvas, instituteName, "OFFICIAL STATEMENT OF MARKS")
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 22, 43)
            textSize = 14f
        }
        var y = 170f
        listOf(
            "Examination" to result.examTitle,
            "Student Name" to result.studentName,
            "Student ID" to result.studentId,
            "Marks Obtained" to "${result.marksObtained} / ${result.totalMarks}",
            "Percentage" to "${(result.marksObtained * 100) / result.totalMarks.coerceAtLeast(1)}%",
            "Grade" to result.grade,
            "Batch Rank" to "#${result.rank}",
            "Result Status" to result.status,
            "Faculty Remarks" to result.remarks.ifBlank { "Good performance" }
        ).forEach { (k, v) ->
            canvas.drawText("$k:", 50f, y, paint)
            canvas.drawText(v, 250f, y, paint)
            y += 34f
        }
        drawFooter(canvas, "EXAMINATION CONTROLLER • WAQAR COMPUTER INSTITUTE")
        pdf.finishPage(page)

        saveAndShareFile(
            context = context,
            fileName = "MarkSheet_${result.studentName.replace(" ", "_")}.pdf",
            mimeType = "application/pdf"
        ) { out -> pdf.writeTo(out) }
        pdf.close()
    }

    fun exportAndSharePayslipPdf(
        context: Context,
        instituteName: String,
        entry: PayrollEntry
    ) {
        val pdf = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = pdf.startPage(pageInfo)
        val canvas = page.canvas

        drawHeader(canvas, instituteName, "STAFF SALARY SLIP • ${entry.monthYear}")
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 22, 43)
            textSize = 14f
        }
        var y = 170f
        listOf(
            "Staff Name" to entry.staffName,
            "Employee ID" to entry.staffId,
            "Payroll Period" to entry.monthYear,
            "Basic Pay" to formatInrPaise(entry.basicPaise),
            "HRA & Allowances" to formatInrPaise(entry.allowancesPaise),
            "PF / TDS / Leave Deductions" to formatInrPaise(entry.deductionsPaise),
            "Leave Days Taken" to "${entry.leaveDays} days",
            "Net Salary Disbursed" to formatInrPaise(entry.netPayPaise),
            "Disbursement Status" to entry.status
        ).forEach { (k, v) ->
            canvas.drawText("$k:", 50f, y, paint)
            canvas.drawText(v, 270f, y, paint)
            y += 34f
        }
        drawFooter(canvas, "HR & PAYROLL DIVISION • WAQAR COMPUTER INSTITUTE")
        pdf.finishPage(page)

        saveAndShareFile(
            context = context,
            fileName = "Payslip_${entry.staffName.replace(" ", "_")}_${entry.monthYear}.pdf",
            mimeType = "application/pdf"
        ) { out -> pdf.writeTo(out) }
        pdf.close()
    }

    fun exportAndShareStudentsCsv(context: Context, students: List<StudentRecord>) {
        val header = "EnrollmentNo,FullName,Phone,Email,Course,Batch,Status,TotalFeeINR,PaidFeeINR,AttendancePercent\n"
        val rows = students.joinToString("\n") { s ->
            "${s.enrollmentNo},\"${s.fullName}\",${s.phone},${s.email},\"${s.courseName}\",\"${s.batchName}\",${s.status},${s.totalFeePaise / 100},${s.paidFeePaise / 100},${s.attendancePercent}"
        }
        val csvContent = (header + rows).toByteArray(Charsets.UTF_8)
        saveAndShareFile(
            context = context,
            fileName = "WCI_Students_Export_${System.currentTimeMillis()}.csv",
            mimeType = "text/csv"
        ) { out -> out.write(csvContent) }
    }

    fun exportAndShareTextOrJson(
        context: Context,
        fileName: String,
        mimeType: String = "application/json",
        content: String
    ) {
        saveAndShareFile(
            context = context,
            fileName = fileName,
            mimeType = mimeType
        ) { out -> out.write(content.toByteArray(Charsets.UTF_8)) }
    }

    private fun drawHeader(canvas: Canvas, instituteName: String, subtitle: String) {
        val bannerPaint = Paint().apply { color = Color.rgb(16, 20, 48) }
        canvas.drawRect(0f, 0f, 595f, 105f, bannerPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 21f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(185, 195, 240)
            textSize = 12f
        }
        canvas.drawText(instituteName, 45f, 52f, titlePaint)
        canvas.drawText(subtitle, 45f, 78f, subPaint)
    }

    private fun drawFooter(canvas: Canvas, footerText: String) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(110, 118, 150)
            textSize = 11f
        }
        canvas.drawLine(45f, 775f, 550f, 775f, paint)
        canvas.drawText(footerText, 45f, 800f, paint)
    }

    private fun saveAndShareFile(
        context: Context,
        fileName: String,
        mimeType: String,
        writer: (FileOutputStream) -> Unit
    ) {
        try {
            val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(exportDir, fileName)
            FileOutputStream(file).use { writer(it) }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, "Export $fileName").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(context, "Saved export: $fileName (${e.localizedMessage ?: "ready"})", Toast.LENGTH_SHORT).show()
        }
    }
}
