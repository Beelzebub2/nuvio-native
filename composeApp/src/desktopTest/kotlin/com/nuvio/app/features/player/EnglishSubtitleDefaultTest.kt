package com.nuvio.app.features.player

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.profiles.ProfileRepository
import org.junit.Test
import kotlin.test.assertEquals

class EnglishSubtitleDefaultTest {
    @Test fun legacyDefaultBecomesEnglishOnceAndLaterChoicesArePreserved() {
        val previous = PlayerSettingsStorage.loadPreferredSubtitleLanguage()
        val store = DesktopStorage.store("nuvio_player_settings")
        val key = "english_subtitle_default_v1_${ProfileRepository.activeProfileId}"
        val previousMarker = store.getString(key)
        try {
            PlayerSettingsStorage.savePreferredSubtitleLanguage(SubtitleLanguageOption.NONE)
            store.remove(key)
            assertEquals("en", PlayerSettingsStorage.loadPreferredSubtitleLanguage())
            PlayerSettingsStorage.savePreferredSubtitleLanguage(SubtitleLanguageOption.NONE)
            assertEquals(SubtitleLanguageOption.NONE, PlayerSettingsStorage.loadPreferredSubtitleLanguage())
            PlayerSettingsStorage.savePreferredSubtitleLanguage("ja")
            assertEquals("ja", PlayerSettingsStorage.loadPreferredSubtitleLanguage())
        } finally {
            PlayerSettingsStorage.savePreferredSubtitleLanguage(previous ?: "en")
            if (previousMarker == null) store.remove(key) else store.putString(key, previousMarker)
        }
    }
}
