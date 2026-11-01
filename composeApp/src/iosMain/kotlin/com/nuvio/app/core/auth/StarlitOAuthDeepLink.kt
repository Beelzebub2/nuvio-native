package com.nuvio.app.core.auth

import com.nuvio.app.core.network.SupabaseProvider
import io.github.jan.supabase.auth.handleDeeplinks
import platform.Foundation.NSURL

/** Invoked by SwiftUI only for the application's OAuth callback URL. */
fun handleStarlitOAuthDeepLink(url: String) {
    if (!url.startsWith("nuvio://auth#", ignoreCase = true) &&
        !url.startsWith("nuvio://auth?", ignoreCase = true)
    ) return
    NSURL.URLWithString(url)?.let {
        AuthRepository.expectRemoteSession()
        SupabaseProvider.client.handleDeeplinks(it)
    }
}
