package com.nuvio.app.core.auth

import androidx.compose.runtime.Composable

// iOS requires Associated Domains and an AuthenticationServices ceremony.
// Keep the control hidden rather than offering a broken or unsafe flow.
@Composable
actual fun rememberPasskeyCeremony(): PasskeyCeremony? = null
