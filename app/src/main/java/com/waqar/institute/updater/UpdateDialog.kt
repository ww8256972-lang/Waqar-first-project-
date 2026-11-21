package com.waqar.institute.updater

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.example.ui.designsystem.JetBrainsMono
import com.example.ui.designsystem.WciColors
import kotlinx.coroutines.launch

/**
 * Non-Cancelable Material 3 Jetpack Compose OTA Update Dialog (`UpdateDialog.kt`).
 *
 * Uses `DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)` so
 * users are prompted with the new GitHub Release version details, linear progress indicator,
 * and an "Update Now" primary action button.
 */
@Composable
fun UpdateDialog(
    releaseInfo: GitHubOtaUpdater.GitHubReleaseInfo,
    onUpdateStarted: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var isLaunchingUpdate by remember { mutableStateOf(false) }
    var progressValue by remember { mutableFloatStateOf(0f) }
    var statusLabel by remember { mutableStateOf("Downloading & Verifying Official Release APK…") }

    AlertDialog(
        onDismissRequest = { /* Non-cancelable mandatory update dialog */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        ),
        containerColor = WciColors.BgElevatedDark,
        icon = {
            Icon(
                imageVector = Icons.Default.SystemUpdate,
                contentDescription = "New Update Available",
                tint = WciColors.Success
            )
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "New Update Available",
                    style = MaterialTheme.typography.titleLarge.copy(
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "v${releaseInfo.currentVersion} → ${releaseInfo.tagName}",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontFamily = JetBrainsMono,
                        color = WciColors.Info,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = releaseInfo.releaseTitle,
                    style = MaterialTheme.typography.titleSmall.copy(
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = WciColors.BgPrimaryDark,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = releaseInfo.releaseNotes,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = WciColors.TextSecondaryDark
                        ),
                        modifier = Modifier.padding(12.dp)
                    )
                }

                if (isLaunchingUpdate) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = statusLabel,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = WciColors.Success
                                )
                            )
                            Text(
                                text = "${(progressValue * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = JetBrainsMono,
                                    color = WciColors.Info
                                )
                            )
                        }
                        LinearProgressIndicator(
                            progress = { progressValue },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .testTag("github_ota_progress_bar"),
                            color = WciColors.Success,
                            trackColor = Color.White.copy(alpha = 0.14f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!isLaunchingUpdate) {
                        isLaunchingUpdate = true
                        progressValue = 0.05f
                        onUpdateStarted()
                        if (releaseInfo.assetUrl.endsWith(".apk", ignoreCase = true)) {
                            scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                val result = GitHubOtaUpdater.downloadAndInstallReleaseApkInApp(
                                    context = context,
                                    releaseInfo = releaseInfo,
                                    onProgress = { pct -> progressValue = pct }
                                )
                                if (result.isFailure) {
                                    statusLabel = "Opening Official GitHub Release…"
                                    progressValue = 1.0f
                                    GitHubOtaUpdater.launchOfficialReleaseUpdate(context, releaseInfo)
                                } else {
                                    statusLabel = "Verified SHA-256 & Certificate Pin — Launching Installer…"
                                    progressValue = 1.0f
                                }
                            }
                        } else {
                            statusLabel = "Opening Official GitHub Release…"
                            progressValue = 1.0f
                            GitHubOtaUpdater.launchOfficialReleaseUpdate(context, releaseInfo)
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = WciColors.Success,
                    contentColor = WciColors.UpdateBannerTextDark
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("github_ota_update_now_button")
            ) {
                Text(
                    text = "Update Now (${releaseInfo.tagName})",
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    )
}
