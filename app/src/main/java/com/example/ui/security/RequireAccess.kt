package com.example.ui.security

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.institute.security.SecurityKit

/**
 * Opt-in Jetpack Compose UI hint gate (Section 4.7).
 * Only controls visual affordances (show/hide); real enforcement happens on the gateway/OpenFGA server.
 */
@Composable
fun RequireAccess(
    relation: String,
    obj: String,
    denied: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    var allowed by remember(relation, obj) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(relation, obj) {
        allowed = if (SecurityKit.isInitialized()) {
            SecurityKit.authz.can(relation, obj)
        } else {
            false
        }
    }
    when (allowed) {
        true -> content()
        false -> denied()
        null -> Unit
    }
}
