package com.nuvio.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartupResumePromptTest {
    @Test
    fun loadingSplashNeverAllowsEpisodePrompt() {
        assertFalse(isStartupResumePromptReady(
            rootContentReady = false,
            splashCurrentState = true,
            splashTargetState = true,
            splashIsIdle = true,
        ))
    }

    @Test
    fun episodePromptWaitsUntilTheEntireSplashExitAnimationFinishes() {
        assertFalse(isStartupResumePromptReady(
            rootContentReady = true,
            splashCurrentState = true,
            splashTargetState = false,
            splashIsIdle = false,
        ))
        assertFalse(isStartupResumePromptReady(
            rootContentReady = true,
            splashCurrentState = false,
            splashTargetState = false,
            splashIsIdle = false,
        ))
        assertTrue(isStartupResumePromptReady(
            rootContentReady = true,
            splashCurrentState = false,
            splashTargetState = false,
            splashIsIdle = true,
        ))
    }

    @Test
    fun profileSwitchHidesEpisodePromptUntilNewHomeIsReady() {
        assertFalse(isStartupResumePromptReady(
            rootContentReady = false,
            splashCurrentState = false,
            splashTargetState = true,
            splashIsIdle = false,
        ))
        assertFalse(isStartupResumePromptReady(
            rootContentReady = true,
            splashCurrentState = false,
            splashTargetState = true,
            splashIsIdle = false,
        ))
    }

    @Test
    fun startupPromptWorksWhenNoSplashIsConfigured() {
        assertTrue(isStartupResumePromptReady(
            rootContentReady = true,
            splashCurrentState = false,
            splashTargetState = false,
            splashIsIdle = true,
        ))
    }
}
