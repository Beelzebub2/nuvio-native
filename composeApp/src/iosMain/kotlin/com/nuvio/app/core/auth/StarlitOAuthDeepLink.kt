package com.nuvio.app.core.auth

/** Invoked by SwiftUI only for the application's OAuth callback URL. */
fun handleStarlitOAuthDeepLink(url: String) {
    AuthRepository.handleGoogleOAuthCallback(url)
}
