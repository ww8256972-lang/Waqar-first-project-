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

data class SyncedProjectFile(
    val filename: String,
    val labPcOrigin: String,
    val language: String,
    val lastSyncTimestamp: String,
    val sizeBytes: Long
)

/**
 * Omni-Channel Project Sync (Priority 4.3.2).
 * Real-time workspace synchronization allowing students to seamlessly pick up code,
 * scripts, and Tally ledgers created on physical Lab PCs directly on their mobile phone.
 */
@Composable
fun StudentProjectSyncScreen(
    state: InstituteUiState,
    viewModel: InstituteViewModel
) {
    val context = LocalContext.current
    var syncedProjects by remember {
        mutableStateOf(
            listOf(
                SyncedProjectFile("employee_payroll.py", "Lab 1 (PC-04)", "Python 3.11", "Synced 10m ago", 4_280),
                SyncedProjectFile("gst_billing_voucher.tally", "Lab 1 (PC-04)", "Tally Prime XML", "Synced Today", 18_400),
                SyncedProjectFile("student_portal_api.cpp", "Lab 2 (PC-11)", "C++20", "Synced Yesterday", 8_920)
            )
        )
    }

    var isSyncingNow by remember { mutableStateOf(false) }

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
                        text = "Omni-Channel Cloud Workspace Sync",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Auto-sync lab computer code & practical assignments to your mobile terminal.",
                        style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textSecondary)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    ShimmerButton(
                        text = if (isSyncingNow) "Syncing with Lab PC…" else "Sync from Assigned Lab PC",
                        onClick = {
                            // TODO: Wire to actual lab PC sync service
                            isSyncingNow = true
                            android.widget.Toast.makeText(
                                context, "Lab PC sync coming soon!", android.widget.Toast.LENGTH_SHORT
                            ).show()
                            isSyncingNow = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        item {
            Text(
                text = "Synced Workspaces (${syncedProjects.size})",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = WciColors.textPrimary
                )
            )
        }

        items(syncedProjects) { project ->
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = WciColors.elevatedSurface),
                border = BorderStroke(1.dp, WciColors.GlassBorderLight),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(project.filename, fontWeight = FontWeight.Bold, color = Color.White, fontFamily = JetBrainsMono, fontSize = 14.sp)
                        Text("${project.language} • ${project.labPcOrigin}", fontSize = 12.sp, color = WciColors.textSecondary)
                        Text(project.lastSyncTimestamp, fontSize = 11.sp, color = WciColors.accentCyan)
                    }
                    Button(
                        onClick = {
                            // TODO: Wire to code viewer / IDE integration
                            android.widget.Toast.makeText(
                                context, "Code viewer coming soon!", android.widget.Toast.LENGTH_SHORT
                            ).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = WciColors.IndigoAccent)
                    ) {
                        Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Open Code", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
