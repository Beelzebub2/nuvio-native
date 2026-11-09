package com.nuvio.app

import androidx.compose.runtime.compositionLocalOf

/** Scoped to the TV activity: regular phones, tablets and desktop remain unchanged. */
internal val LocalTelevisionUi = compositionLocalOf { false }
