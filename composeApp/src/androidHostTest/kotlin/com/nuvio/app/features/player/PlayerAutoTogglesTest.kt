package com.nuvio.app.features.player

import android.app.Application
import android.content.Context
import com.nuvio.app.features.player.skip.AutoSkipSegmentType
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayerAutoTogglesTest {
    @BeforeTest
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_player_settings", Context.MODE_PRIVATE).edit().clear().commit()
        PlayerSettingsStorage.initialize(context)
        PlayerSettingsRepository.clearLocalState()
        PlayerSettingsRepository.ensureLoaded()
    }

    @AfterTest
    fun clearState() {
        PlayerSettingsRepository.clearLocalState()
    }

    private val settings get() = PlayerSettingsRepository.uiState.value

    @Test
    fun bothTogglesStartOff() {
        assertFalse(settings.autoSkipIntroActive)
        assertFalse(settings.streamAutoPlayNextEpisodeEnabled)
    }

    @Test
    fun autoSkipIntroTurnsOnAndSurvivesReload() {
        setAutoSkipIntroFromPlayer(true)
        assertTrue(settings.autoSkipIntroActive)

        PlayerSettingsRepository.onProfileChanged()
        assertTrue(settings.autoSkipIntroActive)
    }

    @Test
    fun autoSkipIntroAlsoTurnsOnSegmentDetection() {
        PlayerSettingsRepository.setSkipIntroEnabled(false)
        assertFalse(settings.autoSkipIntroActive)

        setAutoSkipIntroFromPlayer(true)

        assertTrue(settings.skipIntroEnabled)
        assertTrue(settings.autoSkipIntroActive)
    }

    @Test
    fun turningAutoSkipIntroOffKeepsOtherSegmentsAndManualSkip() {
        PlayerSettingsRepository.setAutoSkipSegmentTypeEnabled(AutoSkipSegmentType.OUTRO, true)
        setAutoSkipIntroFromPlayer(true)

        setAutoSkipIntroFromPlayer(false)

        assertFalse(settings.autoSkipIntroActive)
        assertTrue(settings.skipIntroEnabled)
        assertEquals(setOf(AutoSkipSegmentType.OUTRO), settings.autoSkipSegmentTypes)
    }

    @Test
    fun autoNextEpisodeTogglesAndSurvivesReload() {
        setAutoNextEpisodeFromPlayer(true)
        PlayerSettingsRepository.onProfileChanged()
        assertTrue(settings.streamAutoPlayNextEpisodeEnabled)

        setAutoNextEpisodeFromPlayer(false)
        PlayerSettingsRepository.onProfileChanged()
        assertFalse(settings.streamAutoPlayNextEpisodeEnabled)
    }
}
