package com.nuvio.app.core.auth

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google

internal actual suspend fun launchGoogleOAuth(client: SupabaseClient) {
    client.auth.signInWith(Google)
}
