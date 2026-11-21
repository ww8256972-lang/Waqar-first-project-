package com.waqar.institute.ui.legal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.designsystem.GlassCard
import com.example.ui.designsystem.JetBrainsMono
import com.example.ui.designsystem.WciColors

private data class SecurityPolicyClause(
    val number: Int,
    val title: String,
    val body: String
)

private val MandatorySecurityClauses = listOf(
    SecurityPolicyClause(
        number = 1,
        title = "Official Signed Application Only",
        body = "Users must install and use only the official signed Waqar Computer Institute ERP application. Repackaged, modified, tampered, cloned, or dual-space copies are strictly prohibited and automatically blocked."
    ),
    SecurityPolicyClause(
        number = 2,
        title = "No Unauthorized Device Modification or Tampering",
        body = "Use of rooted devices, Magisk/KernelSU, debuggers, emulators, Frida/Xposed/LSPosed hooking frameworks, memory editors, or reverse-engineering tools against this application is strictly forbidden. Detected tampering triggers immediate security lockdown and audit logging."
    ),
    SecurityPolicyClause(
        number = 3,
        title = "Account Confidentiality & Non-Sharing",
        body = "Login credentials, session tokens, biometric enrollments, and device bindings are strictly personal. Sharing credentials or attempting to log into another student's, parent's, teacher's, or administrator's portal is strictly prohibited."
    ),
    SecurityPolicyClause(
        number = 4,
        title = "Proctored Examination Integrity",
        body = "During online CBT examinations, switching apps, split-screen/multi-window usage, external display mirroring (HDMI/Miracast/Chromecast), screenshots, screen recording, clock manipulation, or answer-key tampering are strictly prohibited and result in automatic exam submission and disciplinary review."
    ),
    SecurityPolicyClause(
        number = 5,
        title = "AI Tutor Acceptable Academic Use",
        body = "The Institute AI Tutor is strictly reserved for Computer Science, Programming, Networking, Databases, Tally/Accounting, MS Office, and IT studies. Prompt injection, jailbreaking, hacking requests, or off-topic abuse triggers automated 24-hour AI Tutor lockouts and permanent security alerts to the Administrator."
    ),
    SecurityPolicyClause(
        number = 6,
        title = "Attendance, Geofencing & Biometric Honesty",
        body = "GPS spoofing, mock location apps, photo/video replay against the Face Biometric camera, or proxy QR attendance scanning are strictly prohibited and logged as critical security incidents."
    ),
    SecurityPolicyClause(
        number = 7,
        title = "Authentic Financial & UPI Transactions",
        body = "Submitting forged UTR numbers, replaying old payment receipts, tampering with fee amounts, or interfering with UPI payment verification is strictly prohibited. All fee approvals and certificates are cryptographically sealed in an immutable server-side audit hash-chain."
    ),
    SecurityPolicyClause(
        number = 8,
        title = "Zero Unauthorized Data Scraping or Capture",
        body = "Screens protected by FLAG_SECURE, study materials, exam banks, and student/institute records may not be captured, scraped, redistributed, or leaked without written authorization from Waqar Computer Institute."
    ),
    SecurityPolicyClause(
        number = 9,
        title = "Offline Practice Simulator Boundary",
        body = "The Tally Practice Simulator is strictly an isolated offline educational sandbox; entries made inside the simulator do not constitute official institute accounting records or statutory GST filings."
    ),
    SecurityPolicyClause(
        number = 10,
        title = "Enforcement Actions & Device Revocation",
        body = "Violation of any of these rules may result in immediate session termination, 24-hour or permanent feature lockout, remote device revocation, exam disqualification, and disciplinary action by Waqar Computer Institute Administration."
    )
)

/**
 * Official Terms & Conditions and Security Policy Screen (`TermsAndConditionsScreen.kt`).
 * Wired strictly as the LAST navigation drawer destination across all 4 portals.
 */
@Composable
fun TermsAndConditionsScreen() {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("terms_and_conditions_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                glowColor = WciColors.infoColor
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Gavel,
                        contentDescription = "Terms and Conditions",
                        tint = WciColors.infoColor,
                        modifier = Modifier.size(28.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Waqar Computer Institute ERP — Terms & Conditions and Security Policy",
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = WciColors.textPrimary,
                                fontWeight = FontWeight.ExtraBold
                            )
                        )
                        Text(
                            text = "Mandatory Compliance Policy • Effective for Admin, Teacher, Student & Parent Portals",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = JetBrainsMono,
                                color = WciColors.infoColor
                            )
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "By accessing or using the Waqar Computer Institute ERP mobile application, you unconditionally agree to comply with the following ten (10) mandatory institutional and security governance rules.",
                    style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textSecondary)
                )
            }
        }

        items(MandatorySecurityClauses, key = { it.number }) { clause ->
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(WciColors.infoColor.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
                            .border(1.dp, WciColors.infoColor.copy(alpha = 0.6f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${clause.number}",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontFamily = JetBrainsMono,
                                fontWeight = FontWeight.ExtraBold,
                                color = WciColors.infoColor
                            )
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = clause.title,
                            style = MaterialTheme.typography.titleSmall.copy(
                                color = WciColors.textPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Text(
                            text = clause.body,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = WciColors.textSecondary
                            )
                        )
                    }
                }
            }
        }

        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                glowColor = WciColors.successColor
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = "Policy Enforcement Notice",
                        tint = WciColors.successColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Institutional Governance & Audit Notice",
                        style = MaterialTheme.typography.titleSmall.copy(
                            color = WciColors.successColor,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "All critical administrative approvals, security alerts, and integrity telemetry are recorded in Waqar Computer Institute's tamper-evident audit ledger. Continued use of this application constitutes ongoing acceptance of this Security Policy.",
                    style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textMuted)
                )
            }
        }
    }
}
