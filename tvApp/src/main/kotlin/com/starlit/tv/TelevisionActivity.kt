package com.starlit.tv

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import com.nuvio.app.MainActivity

/** Dedicated TV entry point; inherits the shared media, account and sync bootstrap. */
class TelevisionActivity : MainActivity() {
    override val televisionExperience: Boolean = true

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The launcher and movie player should use the entire television display.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
        )
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }
}
