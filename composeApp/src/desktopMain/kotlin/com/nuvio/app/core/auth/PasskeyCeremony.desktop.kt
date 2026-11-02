package com.nuvio.app.core.auth

import androidx.compose.runtime.Composable

// Desktop needs a trusted RP-hosted browser bridge. Never attempt WebAuthn from
// an untrusted file:// or localhost context, or collect passkey private keys.
@Composable
actual fun rememberPasskeyCeremony(): PasskeyCeremony? = null
