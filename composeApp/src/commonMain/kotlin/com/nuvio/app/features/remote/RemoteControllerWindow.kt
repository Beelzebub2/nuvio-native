package com.nuvio.app.features.remote

import androidx.compose.runtime.Composable

/**
 * Hosts the remote in its own window: a full-screen page on phones and a separate,
 * non-blocking window on desktop, so the rest of the app stays usable.
 */
@Composable
internal expect fun RemoteControllerWindow(
    title: String,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
)
