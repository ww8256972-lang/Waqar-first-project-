package com.waqar.institute.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VerifiedUser
import com.example.data.model.PortalRole
import com.example.ui.designsystem.WciColors
import com.example.ui.portal.ModuleDestination

/**
 * Dynamic Side Navigation Drawer Provider for the 100% Offline Coding, Testing & Learning Lab
 * Module (`M25_CODING_SANDBOX`), Pro Academic Suite (`M26_ACADEMIC_PRO_SUITE`),
 * UPI & Auto-EMI Vault (`M27_UPI_EMI_COMMAND`), Tally Practice Simulator (`M28_TALLY_SIMULATOR`),
 * and Terms & Conditions (`M29_TERMS_AND_CONDITIONS` — strictly LAST item across all 4 portals).
 */
object AppDrawer {

    const val ROUTE_CODING_SANDBOX: String = "M25_CODING_SANDBOX"
    const val TITLE_CODING_SANDBOX_HINDI: String = "Coding, Testing & Learning Lab"
    const val SHORT_LABEL_CODING_SANDBOX: String = "Coding Lab"

    const val ROUTE_ACADEMIC_PRO_SUITE: String = "M26_ACADEMIC_PRO_SUITE"
    const val TITLE_ACADEMIC_PRO_SUITE: String = "QR+Face, Lab PC, ID PDF & Typing Arena"
    const val SHORT_LABEL_ACADEMIC_PRO_SUITE: String = "Pro Lab"

    const val ROUTE_UPI_EMI_COMMAND: String = "M27_UPI_EMI_COMMAND"
    const val TITLE_UPI_EMI_COMMAND: String = "Zero-Fee UPI & Auto-EMI Vault"
    const val SHORT_LABEL_UPI_EMI_COMMAND: String = "UPI & EMI"

    const val ROUTE_TALLY_SIMULATOR: String = "M28_TALLY_SIMULATOR"
    const val TITLE_TALLY_SIMULATOR: String = "Tally Practice Simulator"
    const val SHORT_LABEL_TALLY_SIMULATOR: String = "Tally Lab"

    const val ROUTE_ADAPTIVE_LEARNING: String = "M30_ADAPTIVE_LEARNING"
    const val TITLE_ADAPTIVE_LEARNING: String = "Adaptive Learning & AI Dashboard"
    const val SHORT_LABEL_ADAPTIVE_LEARNING: String = "Adaptive AI"

    const val ROUTE_TERMS_AND_CONDITIONS: String = "M29_TERMS_AND_CONDITIONS"
    const val TITLE_TERMS_AND_CONDITIONS: String = "Terms & Conditions"
    const val SHORT_LABEL_TERMS_AND_CONDITIONS: String = "Terms"

    val codingSandboxDestination: ModuleDestination = ModuleDestination(
        routeId = ROUTE_CODING_SANDBOX,
        title = TITLE_CODING_SANDBOX_HINDI,
        shortLabel = SHORT_LABEL_CODING_SANDBOX,
        category = "Academics",
        icon = Icons.Default.Terminal,
        accentStart = WciColors.SuccessNeon,
        accentEnd = WciColors.Info
    )

    val academicProSuiteDestination: ModuleDestination = ModuleDestination(
        routeId = ROUTE_ACADEMIC_PRO_SUITE,
        title = TITLE_ACADEMIC_PRO_SUITE,
        shortLabel = SHORT_LABEL_ACADEMIC_PRO_SUITE,
        category = "Academics",
        icon = Icons.Default.VerifiedUser,
        accentStart = WciColors.Info,
        accentEnd = WciColors.SuccessNeon
    )

    val upiAutoEmiVaultDestination: ModuleDestination = ModuleDestination(
        routeId = ROUTE_UPI_EMI_COMMAND,
        title = TITLE_UPI_EMI_COMMAND,
        shortLabel = SHORT_LABEL_UPI_EMI_COMMAND,
        category = "Finance",
        icon = Icons.Default.VerifiedUser,
        accentStart = WciColors.SuccessNeon,
        accentEnd = WciColors.PurpleVibrant
    )

    val tallyPracticeSimulatorDestination: ModuleDestination = ModuleDestination(
        routeId = ROUTE_TALLY_SIMULATOR,
        title = TITLE_TALLY_SIMULATOR,
        shortLabel = SHORT_LABEL_TALLY_SIMULATOR,
        category = "Practice",
        icon = Icons.Default.AccountBalance,
        accentStart = WciColors.ScannerTeal,
        accentEnd = WciColors.SuccessNeon
    )

    val adaptiveLearningDestination: ModuleDestination = ModuleDestination(
        routeId = ROUTE_ADAPTIVE_LEARNING,
        title = TITLE_ADAPTIVE_LEARNING,
        shortLabel = SHORT_LABEL_ADAPTIVE_LEARNING,
        category = "Academics",
        icon = androidx.compose.material.icons.filled.AutoAwesome,
        accentStart = WciColors.PurpleVibrant,
        accentEnd = WciColors.Info
    )

    val termsAndConditionsDestination: ModuleDestination = ModuleDestination(
        routeId = ROUTE_TERMS_AND_CONDITIONS,
        title = TITLE_TERMS_AND_CONDITIONS,
        shortLabel = SHORT_LABEL_TERMS_AND_CONDITIONS,
        category = "Legal",
        icon = Icons.Default.Gavel,
        accentStart = WciColors.Info,
        accentEnd = WciColors.IndigoDeep
    )

    val supportedRoles: Set<PortalRole> = setOf(
        PortalRole.ADMIN,
        PortalRole.TEACHER,
        PortalRole.STUDENT,
        PortalRole.PARENT
    )

    fun isSupportedForRole(role: PortalRole): Boolean = role in supportedRoles
}
