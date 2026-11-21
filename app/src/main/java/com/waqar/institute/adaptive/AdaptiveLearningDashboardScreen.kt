package com.waqar.institute.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.PortalRole
import com.example.ui.designsystem.*
import com.example.viewmodel.InstituteUiState
import com.example.viewmodel.InstituteViewModel
import kotlinx.coroutines.launch

/**
 * Enterprise Adaptive Learning Dashboard Screen (`AdaptiveLearningDashboardScreen.kt`).
 *
 * Implements a unified, asynchronous, role-tailored adaptive learning interface:
 * 1. **Student Portal**: Side-menu accessible personalized learning path, 5-pillar subject mastery
 *    radar, automated AI recommendations with 1-click drill launchers (Coding Sandbox, Tally Simulator,
 *    CBT Mock Exams, LMS Notes).
 * 2. **Teacher Portal**: Class-wide cohort mastery analytics, topic difficulty distribution, and
 *    at-risk student identification for targeted academic interventions.
 * 3. **Admin Portal**: Institute-wide learning velocity KPIs, cohort performance benchmarks, and
 *    syllabus completion vs. concept retention metrics.
 * 4. **Parent Portal**: View-only, tamper-proof academic progress summary of their ward including
 *    attendance impact and actionable parental guidance.
 *
 * All analytical computations run asynchronously off the main thread to guarantee smooth 60/120 FPS
 * Jetpack Compose rendering with zero regression or lag across interface layers.
 */
@Composable
fun AdaptiveLearningDashboardScreen(
    state: InstituteUiState,
    viewModel: InstituteViewModel,
    onNavigateToModule: (String) -> Unit = {}
) {
    val role = state.activePortalRole
    val studentUser = state.userProfile
    val studentId = studentUser?.linkedEntityId?.ifBlank { studentUser.uid } ?: studentUser?.uid.orEmpty()
    val studentName = studentUser?.displayName ?: "Student"

    // Asynchronously loaded states
    var studentProfile by remember { mutableStateOf<AdaptiveLearningEngine.StudentAdaptiveProfile?>(null) }
    var instituteOverview by remember { mutableStateOf<AdaptiveLearningEngine.InstituteAdaptiveOverview?>(null) }
    var parentSummary by remember { mutableStateOf<AdaptiveLearningEngine.ParentAdaptiveSummary?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(state.examResults, state.attendance, state.students, state.courses, role) {
        isLoading = true
        when (role) {
            PortalRole.STUDENT -> {
                studentProfile = AdaptiveLearningEngine.computeStudentProfileAsync(
                    studentId = studentId,
                    studentName = studentName,
                    state = state
                )
            }
            PortalRole.TEACHER, PortalRole.ADMIN -> {
                instituteOverview = AdaptiveLearningEngine.computeInstituteOverviewAsync(state)
            }
            PortalRole.PARENT -> {
                parentSummary = AdaptiveLearningEngine.computeParentSummaryAsync(
                    studentId = studentId,
                    state = state
                )
            }
        }
        isLoading = false
    }

    if (isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(color = WciColors.SuccessNeon)
                Text(
                    text = "Computing Real-Time Adaptive Learning Analytics...",
                    style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textMuted)
                )
            }
        }
        return
    }

    when (role) {
        PortalRole.STUDENT -> {
            StudentAdaptiveView(
                profile = studentProfile,
                onNavigateToModule = onNavigateToModule
            )
        }
        PortalRole.TEACHER -> {
            TeacherAdaptiveView(
                overview = instituteOverview,
                onNavigateToModule = onNavigateToModule
            )
        }
        PortalRole.ADMIN -> {
            AdminAdaptiveView(
                overview = instituteOverview,
                onNavigateToModule = onNavigateToModule
            )
        }
        PortalRole.PARENT -> {
            ParentAdaptiveView(summary = parentSummary)
        }
    }
}

@Composable
private fun StudentAdaptiveView(
    profile: AdaptiveLearningEngine.StudentAdaptiveProfile?,
    onNavigateToModule: (String) -> Unit
) {
    if (profile == null) return

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero Card: Overall Mastery & Velocity
        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                glowColor = WciColors.PurpleVibrant
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(WciColors.PurpleVibrant.copy(alpha = 0.2f), RoundedCornerShape(WciRadius.pill))
                                    .padding(horizontal = 10.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "AI ADAPTIVE LEARNING",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = JetBrainsMono,
                                        fontWeight = FontWeight.Bold,
                                        color = WciColors.CyanBright
                                    )
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .background(WciColors.Success.copy(alpha = 0.2f), RoundedCornerShape(WciRadius.pill))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = profile.learningVelocity,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = JetBrainsMono,
                                        color = WciColors.Success
                                    )
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Concept Mastery Overview",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                color = WciColors.textPrimary,
                                fontWeight = FontWeight.ExtraBold
                            )
                        )
                        Text(
                            text = "Top Strength: ${profile.primaryStrength} • Priority: ${profile.primaryGrowthArea}",
                            style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textSecondary)
                        )
                    }
                    DonutProgressRing(
                        progress = profile.overallMasteryPercent / 100f,
                        color = if (profile.overallMasteryPercent >= 75) WciColors.Success else WciColors.Warning,
                        label = "${profile.overallMasteryPercent}%",
                        subLabel = "Overall"
                    )
                }
            }
        }

        // Section Title: Subject Mastery Breakdown
        item {
            Text(
                text = "5-Pillar Competency Radar",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = WciColors.textPrimary
                )
            )
        }

        // Subject Mastery Bars
        items(profile.subjectMasteries) { subject ->
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = subject.domain.label,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = WciColors.textPrimary
                                )
                            )
                            Text(
                                text = subject.keyConcept,
                                style = MaterialTheme.typography.labelSmall.copy(color = WciColors.textMuted)
                            )
                        }
                        Text(
                            text = "${subject.masteryPercent}% • ${subject.level.label}",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = JetBrainsMono,
                                fontWeight = FontWeight.Bold,
                                color = when (subject.level) {
                                    AdaptiveLearningEngine.MasteryLevel.MASTERED -> WciColors.Success
                                    AdaptiveLearningEngine.MasteryLevel.PROFICIENT -> WciColors.Info
                                    AdaptiveLearningEngine.MasteryLevel.DEVELOPING -> WciColors.Warning
                                    AdaptiveLearningEngine.MasteryLevel.NEEDS_FOCUS -> WciColors.Danger
                                }
                            )
                        )
                    }
                    LinearProgressIndicator(
                        progress = { subject.masteryPercent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = when (subject.level) {
                            AdaptiveLearningEngine.MasteryLevel.MASTERED -> WciColors.Success
                            AdaptiveLearningEngine.MasteryLevel.PROFICIENT -> WciColors.Info
                            AdaptiveLearningEngine.MasteryLevel.DEVELOPING -> WciColors.Warning
                            AdaptiveLearningEngine.MasteryLevel.NEEDS_FOCUS -> WciColors.Danger
                        },
                        trackColor = WciColors.SurfaceDeepNavy
                    )
                    Text(
                        text = "Tip: ${subject.recommendationNote}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = WciColors.textSecondary,
                            fontSize = 11.sp
                        )
                    )
                }
            }
        }

        // Section Title: AI Recommendations
        item {
            Text(
                text = "Targeted AI Practice Interventions",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = WciColors.textPrimary
                )
            )
        }

        // Recommended Action Cards
        items(profile.recommendations) { rec ->
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                glowColor = if (rec.priority == "HIGH") WciColors.Danger else WciColors.Info
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (rec.priority == "HIGH") WciColors.Danger.copy(alpha = 0.2f) else WciColors.Info.copy(alpha = 0.2f),
                                        RoundedCornerShape(4.dp)
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = rec.priority,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = JetBrainsMono,
                                        color = if (rec.priority == "HIGH") WciColors.Danger else WciColors.Info,
                                        fontSize = 10.sp
                                    )
                                )
                            }
                            Text(
                                text = "Est. ${rec.estimatedMinutes} mins",
                                style = MaterialTheme.typography.labelSmall.copy(color = WciColors.textMuted)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = rec.title,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = WciColors.textPrimary
                            )
                        )
                        Text(
                            text = rec.reason,
                            style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textSecondary)
                        )
                    }
                    ShimmerButton(
                        text = rec.actionLabel,
                        onClick = { onNavigateToModule(rec.targetModuleRoute) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TeacherAdaptiveView(
    overview: AdaptiveLearningEngine.InstituteAdaptiveOverview?,
    onNavigateToModule: (String) -> Unit
) {
    if (overview == null) return

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                glowColor = WciColors.CyanBright
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Teacher Course Mastery & Cohort Adaptation",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = WciColors.textPrimary
                        )
                    )
                    Text(
                        text = "Monitor class-wide concept difficulty, student retention, and at-risk learning trajectories.",
                        style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textSecondary)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Total Cohorts", style = MaterialTheme.typography.labelSmall.copy(color = WciColors.textMuted))
                            Text("${overview.cohortSummaries.size}", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = WciColors.textPrimary))
                        }
                        Column {
                            Text("Avg Class Mastery", style = MaterialTheme.typography.labelSmall.copy(color = WciColors.textMuted))
                            Text("${overview.overallInstituteMastery}%", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = WciColors.Success))
                        }
                        Column {
                            Text("At-Risk Learners", style = MaterialTheme.typography.labelSmall.copy(color = WciColors.textMuted))
                            Text("${overview.atRiskLearnersCount}", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = if (overview.atRiskLearnersCount > 0) WciColors.Danger else WciColors.Success))
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = "Course-Wise Adaptive Breakdown",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = WciColors.textPrimary
                )
            )
        }

        items(overview.cohortSummaries) { cohort ->
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = cohort.courseTitle,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = WciColors.textPrimary)
                            )
                            Text(
                                text = "${cohort.totalStudents} Students Enrolled • Retention: ${cohort.retentionRatePercent}%",
                                style = MaterialTheme.typography.labelSmall.copy(color = WciColors.textMuted)
                            )
                        }
                        DonutProgressRing(
                            progress = cohort.avgMasteryPercent / 100f,
                            color = WciColors.Success,
                            label = "${cohort.avgMasteryPercent}%",
                            subLabel = "Cohort",
                            sizeDp = 56.dp
                        )
                    }
                    HorizontalDivider(color = WciColors.borderMedium)
                    Text(
                        text = "Top Mastery: ${cohort.topPerformingTopics.joinToString()}",
                        style = MaterialTheme.typography.bodySmall.copy(color = WciColors.Success)
                    )
                    Text(
                        text = "Needs Review: ${cohort.challengingTopics.joinToString()}",
                        style = MaterialTheme.typography.bodySmall.copy(color = WciColors.Warning)
                    )
                }
            }
        }
    }
}

@Composable
private fun AdminAdaptiveView(
    overview: AdaptiveLearningEngine.InstituteAdaptiveOverview?,
    onNavigateToModule: (String) -> Unit
) {
    if (overview == null) return

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                glowColor = WciColors.PurpleVibrant
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Institute Adaptive Analytics Hub",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = WciColors.textPrimary
                        )
                    )
                    Text(
                        text = "Real-time AI learning velocity aggregation across all academic batches.",
                        style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textSecondary)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Institute Mastery", style = MaterialTheme.typography.labelSmall.copy(color = WciColors.textMuted))
                            Text("${overview.overallInstituteMastery}%", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = WciColors.Success))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Growth Velocity", style = MaterialTheme.typography.labelSmall.copy(color = WciColors.textMuted))
                            Text(overview.systemLearningVelocityTrend, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = WciColors.CyanBright))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Students Assessed", style = MaterialTheme.typography.labelSmall.copy(color = WciColors.textMuted))
                            Text("${overview.totalAssessedStudents}", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = WciColors.textPrimary))
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = "Cohort Performance Benchmarks",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = WciColors.textPrimary
                )
            )
        }

        items(overview.cohortSummaries) { cohort ->
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = cohort.courseTitle,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = WciColors.textPrimary)
                        )
                        Text(
                            text = "Enrolled: ${cohort.totalStudents} • At-Risk: ${cohort.atRiskStudentCount}",
                            style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textSecondary)
                        )
                    }
                    Text(
                        text = "${cohort.avgMasteryPercent}% Mastery",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = JetBrainsMono,
                            fontWeight = FontWeight.Bold,
                            color = if (cohort.avgMasteryPercent >= 75) WciColors.Success else WciColors.Warning
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun ParentAdaptiveView(
    summary: AdaptiveLearningEngine.ParentAdaptiveSummary?
) {
    if (summary == null) return

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Read-only Parent Summary Header
        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                glowColor = WciColors.NeonPinkCoral
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .background(WciColors.NeonPinkCoral.copy(alpha = 0.2f), RoundedCornerShape(WciRadius.pill))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "VIEW-ONLY GUARDIAN REPORT",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = JetBrainsMono,
                                    fontWeight = FontWeight.Bold,
                                    color = WciColors.NeonPinkCoral,
                                    fontSize = 10.sp
                                )
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "${summary.childName}'s Learning Progress",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = WciColors.textPrimary
                            )
                        )
                        Text(
                            text = "Pace: ${summary.academicPace} • ${summary.examCountSummary}",
                            style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textSecondary)
                        )
                    }
                    DonutProgressRing(
                        progress = summary.overallMasteryPercent / 100f,
                        color = WciColors.Success,
                        label = "${summary.overallMasteryPercent}%",
                        subLabel = "Mastery"
                    )
                }
            }
        }

        // Parent Action Tip
        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                glowColor = WciColors.Info
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Lightbulb,
                        contentDescription = "Parent Guidance",
                        tint = WciColors.CyanBright
                    )
                    Column {
                        Text(
                            text = "Parent Guidance & Action Tip",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = WciColors.textPrimary)
                        )
                        Text(
                            text = summary.parentActionTip,
                            style = MaterialTheme.typography.bodySmall.copy(color = WciColors.textSecondary)
                        )
                    }
                }
            }
        }

        // Competency List (View-Only)
        item {
            Text(
                text = "Subject Mastery Breakdown (View-Only)",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = WciColors.textPrimary
                )
            )
        }

        items(summary.subjectMasteries) { subject ->
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = subject.domain.label,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = WciColors.textPrimary)
                        )
                        Text(
                            text = "${subject.masteryPercent}%",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = JetBrainsMono,
                                fontWeight = FontWeight.Bold,
                                color = if (subject.masteryPercent >= 75) WciColors.Success else WciColors.Warning
                            )
                        )
                    }
                    LinearProgressIndicator(
                        progress = { subject.masteryPercent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = if (subject.masteryPercent >= 75) WciColors.Success else WciColors.Warning,
                        trackColor = WciColors.SurfaceDeepNavy
                    )
                }
            }
        }
    }
}
