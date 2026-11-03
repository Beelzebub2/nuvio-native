package com.nuvio.app

/** The resume card belongs to the loaded app, never to the branded loading screen. */
internal fun isStartupResumePromptReady(
    rootContentReady: Boolean,
    splashCurrentState: Boolean,
    splashTargetState: Boolean,
    splashIsIdle: Boolean,
): Boolean = rootContentReady && splashIsIdle && !splashCurrentState && !splashTargetState
