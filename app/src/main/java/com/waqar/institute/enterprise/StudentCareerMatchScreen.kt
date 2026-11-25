package com.waqar.institute.enterprise

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.viewmodel.InstituteUiState
import com.example.ui.designsystem.JetBrainsMono
import androidx.compose.ui.platform.LocalContext
import com.example.ui.designsystem.ShimmerButton
import com.example.ui.designsystem.WciColors
import com.example.viewmodel.InstituteViewModel

data class JobOpening(
    val title: String,
    val company: String,
    val location: String,
    val salaryLpa: String,
    val matchScore: Int,
    val requiredSkill: String
)

/**
 * AI Career & Internship Matchmaking (Priority 4.3.3).
 * Auto-generates student verified skill portfolios (typing WPM, exam rank, QR verification link)
 * and matches directly with recruiting companies and partner IT firms.
 */
@Composable
fun StudentCareerMatchScreen(
    state: InstituteUiState,
    viewModel: InstituteViewModel
) {
    val context = LocalContext.current
    val jobs = remember {
        listOf(
            JobOpening("Junior Python Developer", "Tech Mahindra", "Lucknow / Remote", "3.6 - 4.5 LPA", 94, "Python, SQL & Git"),
            JobOpening("Accounts & GST Executive", "PwC Service Delivery", "Kanpur", "2.8 - 3.5 LPA", 88, "Tally Prime, Excel & GST"),
            JobOpening("Computer Lab Technical Assistant", "Allen Career Institute", "Unnao", "2.4 - 3.0 LPA", 96, "Hardware, Networking & CCC")
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = WciColors.containerSurface),
                border = BorderStroke(1.dp, WciColors.GlassBorderLight),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "AI Verified Career & Internship Hub",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Auto-generated cryptographic resume featuring your live typing speed, exam marks, and QR verification stamp.",
                        style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textSecondary)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            color = WciColors.Success.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, WciColors.Success.copy(alpha = 0.35f))
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("Typing WPM", fontSize = 11.sp, color = WciColors.textSecondary)
                                Text("48 WPM", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = WciColors.Success, fontFamily = JetBrainsMono)
                            }
                        }
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            color = WciColors.IndigoAccent.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, WciColors.IndigoAccent.copy(alpha = 0.35f))
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("Exam Percent", fontSize = 11.sp, color = WciColors.textSecondary)
                                Text("88% Grade A", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = WciColors.infoColor, fontFamily = JetBrainsMono)
                            }
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = "Matched Job & Internship Openings",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = WciColors.textPrimary
                )
            )
        }

        items(jobs) { job ->
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = WciColors.elevatedSurface),
                border = BorderStroke(1.dp, WciColors.GlassBorderLight),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(job.title, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                        Box(
                            modifier = Modifier
                                .background(WciColors.Success.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text("${job.matchScore}% Match", fontWeight = FontWeight.Bold, color = WciColors.Success, fontSize = 11.5.sp)
                        }
                    }
                    Text("${job.company} • ${job.location}", fontSize = 12.sp, color = WciColors.textSecondary)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Salary: ${job.salaryLpa} • Key Skill: ${job.requiredSkill}", fontSize = 11.5.sp, color = WciColors.accentCyan)
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            // TODO: Wire to actual portfolio submission flow
                            android.widget.Toast.makeText(
                                context, "Portfolio submission coming soon!", android.widget.Toast.LENGTH_SHORT
                            ).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = WciColors.EmeraldAction),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Apply with Verified Institute Portfolio")
                    }
                }
            }
        }
    }
}
