package com.nuvio.app.core.auth

import com.nuvio.app.core.network.SupabaseProvider
import io.github.jan.supabase.auth.handleDeeplinks
import platform.Foundation.NSURL

/** Invoked by SwiftUI only for the application's OAuth callback URL. */
fun handleStarlitOAuthDeepLink(url: String) {
    if (!GoogleOAuthAvailability.isOfficialServer() || !isStarlitOAuthCallback(url)) return
    NSURL.URLWithString(url)?.let {
        SupabaseProvider.client.handleDeeplinks(it,
            onError = { AuthRepository.cancelRemoteSessionRequest() })
    }
}
