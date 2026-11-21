package com.waqar.institute.communication

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder

/**
 * [C2] 1-Tap WhatsApp Fee Reminder, 4-Month Auto-EMI, Absent Alert & Exam Result Dispatcher.
 */
object WhatsAppInstituteAlertHelper {

    fun sendWhatsAppMessage(context: Context, rawPhone: String, message: String): Boolean {
        val digits = rawPhone.filter { it.isDigit() }
        if (digits.length < 10) return false
        val phoneWithCc = if (digits.length == 10) "91$digits" else digits
        val encoded = URLEncoder.encode(message, "UTF-8")
        val uri = Uri.parse("https://wa.me/$phoneWithCc?text=$encoded")
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            true
        }.getOrDefault(false)
    }

    fun sendExamResultAlert(
        context: Context,
        guardianPhone: String,
        studentName: String,
        examTitle: String,
        marks: Int,
        total: Int,
        pct: String,
        grade: String
    ): Boolean = sendWhatsAppMessage(
        context = context,
        rawPhone = guardianPhone,
        message = "Waqar Computer Institute Result Alert:\nStudent: $studentName\nExam: $examTitle\nScore: $marks/$total ($pct)\nGrade: $grade\n— Official ERP Notification"
    )

    fun sendFeeOrAutoEmiAlert(
        context: Context,
        guardianPhone: String,
        studentName: String,
        amountInr: Long,
        dueDateIso: String
    ): Boolean = sendWhatsAppMessage(
        context = context,
        rawPhone = guardianPhone,
        message = "Waqar Computer Institute Fee Reminder:\nStudent: $studentName\nAmount Due: Rs. $amountInr (Due: $dueDateIso)\nPay via Official UPI: 9235528409@ptyes (Waqar computer institute)"
    )

    fun sendAbsentAlert(
        context: Context,
        guardianPhone: String,
        studentName: String,
        batchName: String,
        dateIso: String
    ): Boolean = sendWhatsAppMessage(
        context = context,
        rawPhone = guardianPhone,
        message = "Waqar Computer Institute Attendance Alert:\nStudent: $studentName\nBatch: $batchName\nStatus: ABSENT on $dateIso.\nPlease ensure regular attendance."
    )
}
