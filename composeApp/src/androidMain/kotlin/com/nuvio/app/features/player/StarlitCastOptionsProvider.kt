package com.nuvio.app.features.player

import android.content.Context
import androidx.annotation.Keep
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * The public Default Media Receiver handles ordinary unauthenticated video
 * without a custom server, credentials proxy, media relay or app registration.
 * A branded receiver may replace this ID only after its own security review.
 */
@Keep
class StarlitCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
